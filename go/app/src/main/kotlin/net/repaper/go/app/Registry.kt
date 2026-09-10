package net.repaper.go.app

import android.content.Context
import net.repaper.go.core.SheetModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

const val GO_VERSION = "0.2.7"

/** Same shape as the Dock's ~/.repaper/sheets.json: id → {name, transport, address, keys, model}.
 *  The AES key from the QR link lives only here. */
class Registry(context: Context) {
    private val file = File(context.filesDir, "sheets.json")
    private var data = JSONObject()

    init { if (file.exists()) runCatching { data = JSONObject(file.readText()) } }

    private fun save() = file.writeText(data.toString(2))

    fun ids(): List<String> = data.keys().asSequence().toList()

    fun entry(id: String): JSONObject = data.getJSONObject(id)

    fun model(id: String): SheetModel {
        val m = entry(id).getJSONObject("model")
        val inset = m.optJSONArray("inset") ?: JSONArray(listOf(0, 0, 0, 0))
        return SheetModel(m.getInt("width"), m.getInt("height"), m.optString("palette", "BW"),
            IntArray(4) { inset.getInt(it) })
    }

    fun add(id: String, name: String, address: String, keyHex: String?, model: SheetModel) {
        val keys = JSONObject()
        if (keyHex != null) keys.put("key", keyHex)
        data.put(id, JSONObject()
            .put("name", name)
            .put("transport", "opendisplay-ble")
            .put("address", address)
            .put("keys", keys)
            .put("model", JSONObject()
                .put("width", model.width).put("height", model.height)
                .put("palette", model.palette).put("inset", JSONArray(model.inset.toList()))))
        save()
    }

    fun updateKey(id: String, key: String, value: String) {
        entry(id).getJSONObject("keys").put(key, value); save()
    }

    fun rename(id: String, name: String) { entry(id).put("name", name); save() }
    fun remove(id: String) { data.remove(id); save() }

    fun keyHex(id: String): String? = entry(id).getJSONObject("keys").optString("key").ifEmpty { null }
    fun bleAddress(id: String): String? = entry(id).getJSONObject("keys").optString("ble_address").ifEmpty { null }
    fun name(id: String): String = entry(id).optString("name").ifEmpty { id }
    fun address(id: String): String = entry(id).getString("address")
}

/** App-level preferences: the printer name people see in print dialogs.
 *  Defaults to a per-device name so two phones never collide. */
object Prefs {
    const val DEFAULT_CLOUD = "https://repaper.schisch.net"

    private fun p(c: android.content.Context) = c.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)

    /** The cloud this app belongs to — reconfigurable on the sign-in page for on-prem installs. */
    fun cloudBase(context: android.content.Context): String =
        p(context).getString("cloud_base", null)?.trimEnd('/') ?: DEFAULT_CLOUD
    fun setCloudBase(context: android.content.Context, url: String) {
        val v = url.trim().trimEnd('/')
        p(context).edit().putString("cloud_base", v.ifEmpty { DEFAULT_CLOUD }).apply()
    }

    /** Set once this device was claimed into an account; cleared when the fleet removes it. */
    fun isClaimed(context: android.content.Context): Boolean = p(context).getBoolean("claimed_once", false)
    fun setClaimed(context: android.content.Context, v: Boolean) = p(context).edit().putBoolean("claimed_once", v).apply()

    /** Members' devices wait for an admin; the app stays gated until this turns true. */
    fun isApproved(context: android.content.Context): Boolean = p(context).getBoolean("approved", true)
    fun setApproved(context: android.content.Context, v: Boolean) = p(context).edit().putBoolean("approved", v).apply()

    /** With several sheets: print on each in turn instead of asking. */
    fun cycleSheets(context: android.content.Context): Boolean = p(context).getBoolean("sheet_cycle", false)
    fun setCycleSheets(context: android.content.Context, v: Boolean) = p(context).edit().putBoolean("sheet_cycle", v).apply()
    fun cycleIx(context: android.content.Context): Int = p(context).getInt("cycle_ix", 0)
    fun bumpCycleIx(context: android.content.Context) = p(context).edit().putInt("cycle_ix", cycleIx(context) + 1).apply()

    /** Printed-today counter (the phone deletes job files after printing, so it counts). */
    private fun todayKey() = "printed_" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    fun printedToday(context: android.content.Context): Int = p(context).getInt(todayKey(), 0)
    fun bumpPrinted(context: android.content.Context) =
        p(context).edit().putInt(todayKey(), printedToday(context) + 1).apply()

    fun printerName(context: android.content.Context): String {
        val p = context.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
        return p.getString("printer_name", null) ?: "RePaper Go (${android.os.Build.MODEL})"
    }
    fun setPrinterName(context: android.content.Context, name: String) {
        context.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("printer_name", name.trim().ifEmpty { "RePaper Go (${android.os.Build.MODEL})" }).apply()
    }
}

/** Cloud identity, generated once — mirrors the Dock's ~/.repaper/cloud.json. */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    val deviceId: String
    val secret: String
    val claimCode: String

    init {
        if (!prefs.contains("device_id")) {
            val rnd = SecureRandom()
            fun hex(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs.edit()
                .putString("device_id", hex(16))
                .putString("secret", hex(24))
                .putString("claim_code", "${hex(2).uppercase()}-${hex(2).uppercase()}")
                .apply()
        }
        deviceId = prefs.getString("device_id", "")!!
        secret = prefs.getString("secret", "")!!
        claimCode = prefs.getString("claim_code", "")!!
    }
}

/** Print jobs waiting for a sheet, spooled as rendered PDFs in filesDir/jobs. */
class JobStore(context: Context) {
    val dir = File(context.filesDir, "jobs").apply { mkdirs() }
    fun list(): List<File> = (dir.listFiles() ?: emptyArray()).filter { it.extension == "pdf" }.sortedBy { it.name }
    fun newJob(label: String): File = File(dir, "${System.currentTimeMillis()}-${label.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}.pdf")
}
