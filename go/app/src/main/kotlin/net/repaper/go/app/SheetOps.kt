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
    suspend fun describeAndRegister(activity: Activity, registry: Registry, landing: Landing): Capabilities {
        val dev = GattLink.find(activity, landing.name, null)
            ?: throw Exception("couldn't find ${landing.name} nearby — wake the sheet and try again")
        val gatt = GattLink.connect(activity, dev)
        try {
            val od = OdDevice(gatt, landing.keyHex?.hexToBytes())
            if (landing.keyHex != null) od.authenticate()
            val c = od.interrogate()
            DiagLog.log("add ${landing.name}: caps=$c tlv=${od.lastConfigHex}")
            registry.add(landing.name, landing.name, landing.name, landing.keyHex,
                SheetModel(c.viewedWidth, c.viewedHeight, c.scheme.paletteKey))
            registry.updateKey(landing.name, "ble_address", dev.address)
            registry.updateKey(landing.name, "native", "${c.width}x${c.height}")
            registry.updateKey(landing.name, "rotation", c.rotation.toString())
            return c
        } finally { gatt.close() }
    }

    /** The registered sheet a landing link refers to, if any. */
    fun findRegistered(registry: Registry, landing: Landing): String? =
        registry.ids().firstOrNull {
            it.equals(landing.name, ignoreCase = true) || registry.address(it).equals(landing.name, ignoreCase = true)
        }
}
