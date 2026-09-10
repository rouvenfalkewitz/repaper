package net.repaper.go.app

import android.app.Activity
import net.repaper.go.core.Capabilities
import net.repaper.go.core.Landing
import net.repaper.go.core.OdDevice
import net.repaper.go.core.SheetModel
import net.repaper.go.core.hexToBytes

/** Registering a sheet from its landing link (QR or NFC): find it over BLE, read its config,
 *  remember everything. Shared by Settings (paste/scan) and the main screen (tap). */
object SheetOps {
    /** true when the sheet's tag was programmed during registration. */
    var lastTagProgrammed = false; private set

    suspend fun describeAndRegister(activity: Activity, registry: Registry, landing: Landing,
                                    link: String? = null): Capabilities {
        val dev = GattLink.find(activity, landing.name, null)
            ?: throw Exception("couldn't find ${landing.name} nearby — wake the sheet and try again")
        val gatt = GattLink.connect(activity, dev)
        try {
            val od = OdDevice(gatt, landing.keyHex?.hexToBytes())
            if (landing.keyHex != null) od.authenticate()
            val c = od.interrogate()
            DiagLog.log("add ${landing.name}: caps=$c tlv=${od.lastConfigHex}")
            // program the sheet's OWN NFC tag while we're connected — some ship with
            // an empty tag, and this is what makes tap-to-print reliable. Non-fatal.
            lastTagProgrammed = false
            if (link != null) {
                try { od.writeNfcUrl(link); lastTagProgrammed = true; DiagLog.log("nfc tag programmed over BLE") }
                catch (e: Exception) { DiagLog.log("nfc tag write skipped: ${e.message}") }
            }
            registry.add(landing.name, landing.name, landing.name, landing.keyHex,
                SheetModel(c.viewedWidth, c.viewedHeight, c.scheme.paletteKey))
            registry.updateKey(landing.name, "ble_address", dev.address)
            registry.updateKey(landing.name, "native", "${c.width}x${c.height}")
            registry.updateKey(landing.name, "rotation", c.rotation.toString())
            if (link != null) registry.updateKey(landing.name, "landing", link)
            return c
        } finally { gatt.close() }
    }

    /** Re-program a registered sheet's tag (sheet options): reconnect and write. */
    suspend fun programTag(activity: Activity, registry: Registry, id: String) {
        val link = registry.landing(id) ?: throw Exception("no landing link stored — re-add the sheet once via its QR")
        val dev = GattLink.find(activity, registry.address(id), registry.bleAddress(id))
            ?: throw Exception("couldn't find the sheet — wake it and try again")
        val gatt = GattLink.connect(activity, dev)
        try {
            val od = OdDevice(gatt, registry.keyHex(id)?.hexToBytes())
            if (registry.keyHex(id) != null) od.authenticate()
            od.writeNfcUrl(link)
            DiagLog.log("nfc tag re-programmed over BLE: $id")
        } finally { gatt.close() }
    }

    /** The registered sheet a landing link refers to, if any. */
    fun findRegistered(registry: Registry, landing: Landing): String? =
        registry.ids().firstOrNull {
            it.equals(landing.name, ignoreCase = true) || registry.address(it).equals(landing.name, ignoreCase = true)
        }
}
