import Foundation
import CoreBluetooth
import RePaperKit

/// The phone's own radio as a SheetTransport: same OdLink contract the core drives,
/// same single-characteristic GATT layout as the Dock's SDK and Android's GattLink.
/// All calls are sequential (the protocol is stop-and-wait). CoreBluetooth callbacks
/// arrive on the main queue; every piece of mutable state is touched only there.
@MainActor final class SheetRadio: NSObject, CBCentralManagerDelegate {
    static let shared = SheetRadio()
    private var central: CBCentralManager!
    private var powerWaiters: [CheckedContinuation<Void, Error>] = []
    private var scanHandler: (@MainActor (CBPeripheral, [String: Any]) -> Void)?
    private var findCont: CheckedContinuation<CBPeripheral, Error>?
    private var connectCont: CheckedContinuation<Void, Error>?
    private var connecting: CBPeripheral?
    // connected links by peripheral, so disconnects reach the right one
    private var links: [UUID: BleLink] = [:]

    private override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    private func waitPoweredOn() async throws {
        switch central.state {
        case .poweredOn: return
        case .unsupported: throw OdError.protocolError("Bluetooth is not available on this device")
        case .unauthorized: throw OdError.protocolError("allow Bluetooth for RePaper Go in Settings")
        case .poweredOff: throw OdError.protocolError("Bluetooth is switched off")
        default:
            try await withCheckedThrowingContinuation { powerWaiters.append($0) }
        }
    }

    nonisolated func centralManagerDidUpdateState(_ c: CBCentralManager) {
        MainActor.assumeIsolated {
            let waiters = powerWaiters; powerWaiters = []
            for w in waiters {
                switch c.state {
                case .poweredOn: w.resume()
                case .unsupported: w.resume(throwing: OdError.protocolError("Bluetooth is not available on this device"))
                case .unauthorized: w.resume(throwing: OdError.protocolError("allow Bluetooth for RePaper Go in Settings"))
                case .poweredOff: w.resume(throwing: OdError.protocolError("Bluetooth is switched off"))
                default: powerWaiters.append(w)   // still settling
                }
            }
        }
    }

    // ── scanning ─────────────────────────────────────────────────────────────

    private func finishFind(_ r: Result<CBPeripheral, Error>) {
        guard let cont = findCont else { return }
        findCont = nil; scanHandler = nil; central.stopScan()
        cont.resume(with: r)
    }

    /// Find a sheet by BLE name (OD…); returns its peripheral.
    func find(name: String, timeoutMs: Int = 12_000) async throws -> CBPeripheral {
        try await waitPoweredOn()
        return try await withCheckedThrowingContinuation { cont in
            findCont = cont
            scanHandler = { [weak self] p, adv in
                let n = p.name ?? adv[CBAdvertisementDataLocalNameKey] as? String
                if n?.caseInsensitiveCompare(name) == .orderedSame { self?.finishFind(.success(p)) }
            }
            central.scanForPeripherals(withServices: nil)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
                self?.finishFind(.failure(OdError.timeout("couldn't find \(name) nearby — wake the sheet and try again")))
            }
        }
    }

    /// Nearby OpenDisplay sheets (manufacturer data 0x2446): name → adv bytes.
    func discover(timeoutMs: Int = 6_000) async throws -> [String: Data] {
        try await waitPoweredOn()
        var hits: [String: Data] = [:]
        scanHandler = { p, adv in
            guard let md = adv[CBAdvertisementDataManufacturerDataKey] as? Data, md.count >= 2 else { return }
            let mfr = Int(md[md.startIndex]) | Int(md[md.startIndex + 1]) << 8
            guard mfr == Od.manufacturerId, let n = p.name ?? adv[CBAdvertisementDataLocalNameKey] as? String else { return }
            hits[n] = Data(md.dropFirst(2))
        }
        central.scanForPeripherals(withServices: nil)
        try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
        scanHandler = nil; central.stopScan()
        return hits
    }

    // ── connecting ───────────────────────────────────────────────────────────

    fileprivate func finishConnect(_ r: Result<Void, Error>) {
        guard let cont = connectCont else { return }
        connectCont = nil; connecting = nil
        cont.resume(with: r)
    }

    /// Connect, discover the 0x2446 service, subscribe to notifications.
    func connect(_ peripheral: CBPeripheral, timeoutMs: Int = 20_000) async throws -> BleLink {
        let link = BleLink(peripheral: peripheral, radio: self)
        links[peripheral.identifier] = link
        peripheral.delegate = link
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connectCont = cont
            connecting = peripheral
            central.connect(peripheral)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
                guard let self, self.connecting === peripheral else { return }
                self.central.cancelPeripheralConnection(peripheral)
                self.finishConnect(.failure(OdError.timeout("connect timed out")))
            }
        }
        return link
    }

    func disconnect(_ peripheral: CBPeripheral) {
        links.removeValue(forKey: peripheral.identifier)
        central.cancelPeripheralConnection(peripheral)
    }

    nonisolated func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                                    advertisementData: [String: Any], rssi: NSNumber) {
        MainActor.assumeIsolated { scanHandler?(p, advertisementData) }
    }

    nonisolated func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        MainActor.assumeIsolated { links[p.identifier]?.startDiscovery() }
    }

    nonisolated func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            links.removeValue(forKey: p.identifier)
            if connecting === p { finishConnect(.failure(OdError.protocolError("BLE connect failed: \(error?.localizedDescription ?? "unknown")"))) }
        }
    }

    nonisolated func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            links.removeValue(forKey: p.identifier)?.fail(OdError.protocolError("BLE link dropped"))
            if connecting === p { finishConnect(.failure(OdError.protocolError("BLE link dropped during connect"))) }
        }
    }
}

/// One live GATT connection implementing OdLink for the RePaperKit core.
@MainActor final class BleLink: NSObject, CBPeripheralDelegate {
    private let peripheral: CBPeripheral
    private unowned let radio: SheetRadio

    private var char_: CBCharacteristic?
    private var inbox: [Data] = []
    private var reader: (seq: Int, cont: CheckedContinuation<Data, Error>)?
    private var writer: (seq: Int, cont: CheckedContinuation<Void, Error>)?
    private var seq = 0
    private var dead: Error?

    init(peripheral: CBPeripheral, radio: SheetRadio) {
        self.peripheral = peripheral
        self.radio = radio
    }

    func close() { radio.disconnect(peripheral) }

    func fail(_ e: Error) {
        dead = e
        radio.finishConnect(.failure(e))
        if let r = reader { reader = nil; r.cont.resume(throwing: e) }
        if let w = writer { writer = nil; w.cont.resume(throwing: e) }
    }

    // ── connection bring-up ──────────────────────────────────────────────────

    func startDiscovery() {
        peripheral.discoverServices([CBUUID(string: Od.serviceUUID)])
    }

    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let svc = p.services?.first(where: { $0.uuid == CBUUID(string: Od.serviceUUID) }) else {
                fail(OdError.protocolError("sheet does not expose the OpenDisplay service")); return
            }
            p.discoverCharacteristics(nil, for: svc)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            guard let ch = service.characteristics?.first else {
                fail(OdError.protocolError("OpenDisplay service has no characteristic")); return
            }
            char_ = ch
            p.setNotifyValue(true, for: ch)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if let error { fail(OdError.protocolError("could not enable notifications: \(error.localizedDescription)")) }
            else { radio.finishConnect(.success(())) }
        }
    }

    // ── data plane ───────────────────────────────────────────────────────────

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let v = c.value else { return }
            if let r = reader { reader = nil; r.cont.resume(returning: v) }
            else { inbox.append(v) }
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let w = writer else { return }
            writer = nil
            if let error { w.cont.resume(throwing: OdError.protocolError("BLE write failed: \(error.localizedDescription)")) }
            else { w.cont.resume() }
        }
    }
}

/// The async OdLink face. iOS always writes with response — simplest reliable mode,
/// and the stop-and-wait protocol never benefits from write-without-response here.
extension BleLink: OdLink {
    func write(_ data: Data, withResponse: Bool) async throws {
        if let dead { throw dead }
        guard let ch = char_ else { throw OdError.protocolError("not connected") }
        seq += 1
        let s = seq
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            writer = (s, cont)
            peripheral.writeValue(data, for: ch, type: .withResponse)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard let self, let w = self.writer, w.seq == s else { return }
                self.writer = nil
                w.cont.resume(throwing: OdError.timeout("BLE write timed out"))
            }
        }
    }

    func read(timeoutMs: Int) async throws -> Data {
        if let dead { throw dead }
        if !inbox.isEmpty { return inbox.removeFirst() }
        seq += 1
        let s = seq
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            reader = (s, cont)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
                guard let self, let r = self.reader, r.seq == s else { return }
                self.reader = nil
                r.cont.resume(throwing: OdError.timeout("no response within \(timeoutMs)ms"))
            }
        }
    }
}

/// Registering a sheet from its landing link (QR, NFC, or pasted): find it over BLE,
/// read its config, remember everything — Android's SheetOps, Swift edition.
enum SheetOps {
    @MainActor static func describeAndRegister(_ landing: Landing, link rawLink: String? = nil) async throws -> Capabilities {
        let peripheral = try await SheetRadio.shared.find(name: landing.name)
        let link = try await SheetRadio.shared.connect(peripheral)
        defer { link.close() }
        let od = OdDevice(link: link, masterKey: landing.keyHex.flatMap { Data(hexString: $0) })
        if landing.keyHex != nil { try await od.authenticate() }
        let caps = try await od.interrogate()
        let tlv = await od.lastConfigHex
        DiagLog.log("add \(landing.name): caps=\(caps) tlv=\(tlv ?? "-")")
        // program the sheet's OWN NFC tag while we're connected — some ship with an
        // empty tag, and this is what makes tap-to-print reliable. Non-fatal: older
        // firmware without the endpoint just logs.
        if let rawLink {
            do { try await od.writeNfcUrl(rawLink); DiagLog.log("nfc tag programmed over BLE") }
            catch { DiagLog.log("nfc tag write skipped: \(error.localizedDescription)") }
        }
        SheetStore.shared.add(Sheet(id: landing.name, name: landing.name, address: landing.name,
                                    keyHex: landing.keyHex, bleAddress: peripheral.identifier.uuidString,
                                    landingUrl: rawLink,
                                    model: SheetModel(width: caps.viewedWidth, height: caps.viewedHeight,
                                                      palette: caps.scheme.paletteKey)))
        return caps
    }

    /// Re-program a registered sheet's tag (sheet options): reconnect and write.
    @MainActor static func programTag(_ sheet: Sheet) async throws {
        guard let url = sheet.landingUrl else { throw OdError.protocolError("no landing link stored — re-add the sheet once via its QR") }
        let peripheral = try await SheetRadio.shared.find(name: sheet.address)
        let link = try await SheetRadio.shared.connect(peripheral)
        defer { link.close() }
        let od = OdDevice(link: link, masterKey: sheet.keyHex.flatMap { Data(hexString: $0) })
        if sheet.keyHex != nil { try await od.authenticate() }
        try await od.writeNfcUrl(url)
        DiagLog.log("nfc tag re-programmed over BLE: \(sheet.id)")
    }
}
