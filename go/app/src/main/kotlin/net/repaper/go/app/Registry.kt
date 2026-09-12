package net.repaper.go.app

import android.content.Context
import net.repaper.go.core.LandingUrl
import net.repaper.go.core.SheetModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

const val GO_VERSION = "0.2.18"

/** Sheets inherited from the paired Dock (Print2Go) — a live, in-memory mirror the cloud
 *  keeps current via {t:dock_sheets}. Overlaid onto the phone's own sheets by Registry, so
 *  a Dock-Label prints and taps exactly like a scanned one. The one bidirectional field is
 *  the NFC tag: learning it on the phone updates here optimistically and syncs up. */
object InheritedSheets {
    @Volatile var entries: List<JSONObject> = emptyList()
    @Volatile var dockName: String? = null
    /** Whoever shows sheets sets this to be nudged when the inherited set changes. */
    @Volatile var onChange: (() -> Unit)? = null

    fun set(sheets: JSONArray, dock: String?) {
        entries = (0 until sheets.length()).map { sheets.getJSONObject(it) }
        dockName = dock?.ifEmpty { null }
        onChange?.invoke()
    }
    fun setTag(sheetId: String, uid: String) {
        entries.firstOrNull { it.optString("id") == sheetId }?.put("tag_uid", uid)
        onChange?.invoke()
    }
}

/** Same shape as the Dock's ~/.repaper/sheets.json: id → {name, transport, address, keys, model}.
 *  The AES key from the QR link lives only here. `data` is the merged view (the phone's own
 *  sheets overlaid with inherited Dock-Labels); `local` is what gets persisted. */
class Registry(context: Context) {
    private val file = File(context.filesDir, "sheets.json")
    private var local = JSONObject()
    private var data = JSONObject()

    init {
        if (file.exists()) runCatching { local = JSONObject(file.readText()) }
        rebuild()
    }

    /** Merge: start from the phone's own sheets, then overlay each inherited sheet (a Dock
     *  present sheet wins by landing name; a locally-learned tag survives if the Dock has none). */
    private fun rebuild() {
        data = JSONObject(local.toString())
        for (e in InheritedSheets.entries) {
            val id = e.optString("id"); if (id.isEmpty()) continue
            val link = e.optString("link").ifEmpty { null }
            val keyHex = link?.let { runCatching { LandingUrl.parse(it).keyHex }.getOrNull() }
            val tag = e.optString("tag_uid").ifEmpty {
                local.optJSONObject(id)?.optJSONObject("keys")?.optString("tag_uid")?.ifEmpty { null }
            }
            val keys = JSONObject()
            if (keyHex != null) keys.put("key", keyHex)
            if (link != null) keys.put("landing", link)
            if (tag != null) keys.put("tag_uid", tag)
            val model = runCatching { JSONObject(e.optString("model", "{}")) }.getOrElse { JSONObject() }
            data.put(id, JSONObject()
                .put("name", e.optString("name").ifEmpty { id })
                .put("transport", "opendisplay-ble")
                .put("address", e.optString("address").ifEmpty { id })
                .put("keys", keys)
                .put("model", model)
                .put("_dock", InheritedSheets.dockName ?: ""))
        }
    }

    private fun save() = file.writeText(local.toString(2))

    fun ids(): List<String> = data.keys().asSequence().toList()
    fun entry(id: String): JSONObject = data.getJSONObject(id)

    fun model(id: String): SheetModel {
        val m = entry(id).getJSONObject("model")
        val inset = m.optJSONArray("inset") ?: JSONArray(listOf(0, 0, 0, 0))
        return SheetModel(m.getInt("width"), m.getInt("height"), m.optString("palette", "BW"),
            IntArray(4) { inset.optInt(it, 0) })
    }

    fun add(id: String, name: String, address: String, keyHex: String?, model: SheetModel) {
        val keys = JSONObject()
        if (keyHex != null) keys.put("key", keyHex)
        local.put(id, JSONObject()
            .put("name", name)
            .put("transport", "opendisplay-ble")
            .put("address", address)
            .put("keys", keys)
            .put("model", JSONObject()
                .put("width", model.width).put("height", model.height)
                .put("palette", model.palette).put("inset", JSONArray(model.inset.toList()))))
        save(); rebuild()
    }

    /** Update a key in the merged view; persists only when it's the phone's own sheet. */
    fun updateKey(id: String, key: String, value: String) {
        data.optJSONObject(id)?.getJSONObject("keys")?.put(key, value)
        local.optJSONObject(id)?.getJSONObject("keys")?.put(key, value)?.also { save() }
    }

    fun rename(id: String, name: String) {
        if (isDockLabel(id)) return                    // a Dock-Label is read-only (lives on the Dock)
        local.optJSONObject(id)?.put("name", name); save(); rebuild()
    }
    fun remove(id: String) {
        if (isDockLabel(id)) return                    // can't remove an inherited sheet from here
        local.remove(id); save(); rebuild()
    }

    fun keyHex(id: String): String? = entry(id).getJSONObject("keys").optString("key").ifEmpty { null }
    fun bleAddress(id: String): String? = entry(id).getJSONObject("keys").optString("ble_address").ifEmpty { null }
    /** The original QR link — needed to (re)program the sheet's NFC tag. */
    fun landing(id: String): String? = entry(id).getJSONObject("keys").optString("landing").ifEmpty { null }
    fun name(id: String): String = entry(id).optString("name").ifEmpty { id }
    fun address(id: String): String = entry(id).getString("address")
    fun tagUid(id: String): String? = entry(id).getJSONObject("keys").optString("tag_uid").ifEmpty { null }
    /** Non-null ⇒ inherited from that Dock (a Dock-Label). */
    fun dockName(id: String): String? = data.optJSONObject(id)?.optString("_dock")?.ifEmpty { null }
    fun isDockLabel(id: String): Boolean = dockName(id) != null
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

    /** Print2Go: the Dock this phone prints from (name, display cache; cloud is the truth). */
    fun print2goDock(context: android.content.Context): String? = p(context).getString("print2go_dock", null)
    fun setPrint2goDock(context: android.content.Context, name: String?) =
        p(context).edit().apply { if (name == null) remove("print2go_dock") else putString("print2go_dock", name) }.apply()
    fun print2goOffered(context: android.content.Context): Boolean = p(context).getBoolean("print2go_offered", false)
    fun setPrint2goOffered(context: android.content.Context, v: Boolean) = p(context).edit().putBoolean("print2go_offered", v).apply()
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

/** Print jobs waiting for a sheet: rendered PDFs from the print service, plus
 *  PNGs relayed from a Dock Light — all in filesDir/jobs. */
class JobStore(context: Context) {
    val dir = File(context.filesDir, "jobs").apply { mkdirs() }
    fun list(): List<File> = (dir.listFiles() ?: emptyArray())
        .filter { it.extension.lowercase() in setOf("pdf", "png", "jpg", "jpeg") }.sortedBy { it.name }
    fun newJob(label: String, ext: String = "pdf"): File =
        File(dir, "${System.currentTimeMillis()}-${label.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")}.$ext")
}
