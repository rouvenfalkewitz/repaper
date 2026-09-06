package net.repaper.go.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.repaper.go.core.Od
import net.repaper.go.core.OdError
import net.repaper.go.core.OdLink
import java.util.UUID

/** The phone's own radio as a SheetTransport: same OdLink contract the core drives,
 *  same single-characteristic GATT layout the Dock's SDK uses. All calls are sequential
 *  (the protocol is stop-and-wait), so one Channel of notifications suffices. */
@SuppressLint("MissingPermission")   // callers gate on runtime permissions in MainActivity
class GattLink private constructor(private val gatt: BluetoothGatt, private val char_: BluetoothGattCharacteristic,
                                   private val notifications: Channel<ByteArray>,
                                   private val writeDone: Channel<Int>) : OdLink {

    override suspend fun write(data: ByteArray, withResponse: Boolean) {
        val type = if (withResponse || (char_.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(char_, data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { char_.writeType = type; char_.value = data; gatt.writeCharacteristic(char_) }
        }
        if (!ok) throw OdError("BLE write refused")
        val status = withTimeoutOrNull(10_000) { writeDone.receive() } ?: throw OdError("BLE write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw OdError("BLE write failed (status $status)")
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { notifications.receive() } ?: throw OdError("no response within ${timeoutMs}ms")

    fun close() { runCatching { gatt.disconnect() }; runCatching { gatt.close() } }

    companion object {
        private val SERVICE = UUID.fromString(Od.SERVICE_UUID)
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Find a sheet by BLE name (OD…) or cached MAC; returns its device or null. */
        suspend fun find(context: Context, name: String, cachedMac: String?, timeoutMs: Long = 12_000): BluetoothDevice? {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw OdError("Bluetooth is not available")
            if (!adapter.isEnabled) throw OdError("Bluetooth is switched off")
            if (cachedMac != null && BluetoothAdapter.checkBluetoothAddress(cachedMac)) {
                return adapter.getRemoteDevice(cachedMac)
            }
            val found = CompletableDeferred<BluetoothDevice?>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val n = result.device.name ?: result.scanRecord?.deviceName
                    if (n?.equals(name, ignoreCase = true) == true) found.complete(result.device)
                }
            }
            val scanner = adapter.bluetoothLeScanner ?: throw OdError("BLE scanner unavailable")
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            val dev = withTimeoutOrNull(timeoutMs) { found.await() }
            scanner.stopScan(cb)
            return dev
        }

        /** Nearby OpenDisplay sheets (manufacturer data 0x2446): name → (mac, adv bytes). */
        suspend fun discover(context: Context, timeoutMs: Long = 6_000): Map<String, Pair<String, ByteArray>> {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return emptyMap()
            if (!adapter.isEnabled) return emptyMap()
            val hits = LinkedHashMap<String, Pair<String, ByteArray>>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val md = result.scanRecord?.getManufacturerSpecificData(Od.MANUFACTURER_ID) ?: return
                    val n = result.device.name ?: result.scanRecord?.deviceName ?: return
                    hits[n] = Pair(result.device.address, md)
                }
            }
            val scanner = adapter.bluetoothLeScanner ?: return emptyMap()
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            kotlinx.coroutines.delay(timeoutMs)
            scanner.stopScan(cb)
            return hits
        }

        /** Connect, discover the 0x2446 service, request a big MTU, subscribe to notifications. */
        suspend fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long = 20_000): GattLink {
            val notifications = Channel<ByteArray>(Channel.UNLIMITED)
            val writeDone = Channel<Int>(Channel.UNLIMITED)
            val ready = CompletableDeferred<Unit>()
            var link: GattLink? = null

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247)
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        ready.completeExceptionally(OdError("BLE link dropped (status $status)"))
                        notifications.close()
                    }
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val svc = g.getService(SERVICE)
                    if (svc == null || svc.characteristics.isEmpty()) {
                        ready.completeExceptionally(OdError("sheet does not expose the OpenDisplay service")); return
                    }
                    val ch = svc.characteristics[0]
                    g.setCharacteristicNotification(ch, true)
                    val cccd = ch.getDescriptor(CCCD)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, byteArrayOf(1, 0))
                        else @Suppress("DEPRECATION") run { cccd.value = byteArrayOf(1, 0); g.writeDescriptor(cccd) }
                    } else ready.complete(Unit)
                    link = GattLink(g, ch, notifications, writeDone)
                }
                override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
                    else ready.completeExceptionally(OdError("could not enable notifications (status $status)"))
                }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    writeDone.trySend(status)
                }
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    notifications.trySend(value)
                }
                @Deprecated("pre-33")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") notifications.trySend(c.value ?: return)
                }
            }

            val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            try { withTimeout(timeoutMs) { ready.await() } }
            catch (e: Exception) { runCatching { gatt.disconnect() }; runCatching { gatt.close() }; throw if (e is OdError) e else OdError("connect timed out") }
            return link ?: throw OdError("connect failed")
        }
    }
}
