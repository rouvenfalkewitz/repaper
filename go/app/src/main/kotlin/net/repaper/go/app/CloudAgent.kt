package net.repaper.go.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One outbound WebSocket to RePaper Cloud — the Dock's cloud.py in miniature, kind "go".
 *  Printing never depends on it; the cloud sees metadata, never pages. */
class CloudAgent(private val context: Context) {
    companion object {
        @Volatile private var instance: CloudAgent? = null
        fun get(context: Context): CloudAgent =
            instance ?: synchronized(this) { instance ?: CloudAgent(context.applicationContext).also { instance = it } }
    }

    private val identity = Identity(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    @Volatile var state: String = "off"; private set
    @Volatile var claimed: Boolean = false; private set
    @Volatile var approved: Boolean = true; private set
    @Volatile var org: String? = null; private set
    val claimCode: String get() = identity.claimCode

    /** A Print2Go job offered but not yet claimed — first to actually print wins. */
    data class Pending(val id: String, val name: String, val from: String)
    @Volatile var pending: List<Pending> = emptyList(); private set

    /** The FCM registration token — handed over so a closed app can be woken for a Dock job.
     *  Set from the Firebase messaging service; relayed to the cloud on each connect. */
    @Volatile private var pushToken: String? = null
    fun setPushToken(token: String) { pushToken = token; sendPushToken() }
    private fun sendPushToken() {
        val t = pushToken ?: return
        ws?.send(JSONObject().put("t", "push_token").put("token", t).put("env", "fcm").toString())
    }

    fun start() {
        if (state != "off") return
        state = "connecting"
        scope.launch { runLoop() }
    }

    fun stop() { ws?.close(1000, null); scope.cancel(); state = "off" }

    private suspend fun runLoop() {
        var backoff = 2_000L
        while (true) {
            val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val url = Prefs.cloudBase(context).replaceFirst("http", "ws") + "/ws/device"
            val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject()
                        .put("t", "hello").put("id", identity.deviceId).put("secret", identity.secret)
                        .put("claim", identity.claimCode).put("kind", "go").put("platform", "android")
                        .put("version", GO_VERSION).toString())
                    opened.complete(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) { handle(webSocket, JSONObject(text)) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    state = "error"; opened.complete(false); closed.complete(Unit)
                }
            })
            ws = socket
            if (opened.await()) {
                state = "online"; backoff = 2_000L
                sendPushToken()   // hand over the FCM token (if we have one) each connect
                // status heartbeat while the link lives
                scope.launch {
                    while (state == "online" && !closed.isCompleted) { sendStatus(socket); delay(300_000) }
                }
            }
            closed.await()
            if (state != "error") state = "connecting"
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun handle(socket: WebSocket, msg: JSONObject) {
        when (msg.optString("t")) {
            "hello_ok" -> {
                claimed = msg.optBoolean("claimed", false)
                approved = msg.optBoolean("approved", true)
                org = msg.optString("org").ifEmpty { null }
                Prefs.setClaimed(context, claimed)   // fleet removed us → the sign-in gate returns
                if (claimed) Prefs.setApproved(context, approved)
            }
            "claimed" -> {
                claimed = true; approved = msg.optBoolean("approved", true)
                org = msg.optString("org").ifEmpty { null }
                Prefs.setClaimed(context, true); Prefs.setApproved(context, approved)
                sendStatus(socket)
            }
            "identify" -> {} // a phone has no LED ring; the app could vibrate later
            "signed_out" -> {
                claimed = false; Prefs.setClaimed(context, false)   // the sign-in gate returns
                onJobArrived?.invoke()
            }
            "mirror_job" -> {
                // a Dock offered a job to the pool — remember it; we only claim when we print
                val job = msg.optJSONObject("job") ?: return
                val id = job.optString("id")
                if (id.isNotEmpty() && pending.none { it.id == id }) {
                    pending = pending + Pending(id, job.optString("name", "job"), job.optString("from", "a Dock"))
                    onJobArrived?.invoke()
                }
            }
            "mirror_taken", "mirror_done" -> {
                val id = msg.optJSONObject("job")?.optString("id") ?: return
                pending = pending.filterNot { it.id == id }
                onJobArrived?.invoke()
            }
            "dock_sheets" -> {
                // the paired Dock's sheets — inherited as Dock-Labels (replaced wholesale)
                InheritedSheets.set(msg.optJSONArray("sheets") ?: org.json.JSONArray(),
                                    msg.optString("dock_name").ifEmpty { null })
                onJobArrived?.invoke()
            }
            "sheet_nfc" -> {
                // a tag learned elsewhere (Dock or another phone) — adopt it on the matching sheet
                val id = msg.optString("sheet_id"); val uid = msg.optString("uid")
                if (id.isNotEmpty() && uid.isNotEmpty()) InheritedSheets.setTag(id, uid)
            }
            "diag" -> socket.send(JSONObject().put("t", "diag").put("log", DiagLog.dump()).toString())
        }
    }

    /** The main screen (when open) refreshes the moment the pending set changes. */
    @Volatile var onJobArrived: (() -> Unit)? = null

    /** Claim a job + get its page (first wins). Returns the spooled file, or null if lost. */
    fun takeJob(id: String): java.io.File? {
        pending = pending.filterNot { it.id == id }
        val obj = post("mirror-job/$id/take") ?: return null
        if (!obj.optBoolean("ok")) { DiagLog.log("take $id: lost the race or gone"); return null }
        val bytes = android.util.Base64.decode(obj.optString("data"), android.util.Base64.DEFAULT)
        val ext = if (obj.optString("type") == "pdf") "pdf" else "png"
        return JobStore(context).newJob(obj.optString("name", "job"), ext).apply { writeBytes(bytes) }
    }
    fun jobDone(id: String) { post("mirror-job/$id/done") }
    fun jobReleased(id: String) { post("mirror-job/$id/release") }

    /** Relay a learned NFC tag up so the paired Dock and other phones converge (the one
     *  field that syncs back). Optimistically reflected locally too. */
    fun sendSheetNfc(sheetId: String, uid: String, programmed: Boolean = false) {
        InheritedSheets.setTag(sheetId, uid)
        ws?.send(JSONObject().put("t", "sheet_nfc").put("sheet_id", sheetId)
            .put("uid", uid).put("programmed", programmed).toString())
    }

    /** The org's Print2Go Docks this phone can print from. */
    fun print2goDocks(): List<JSONObject> {
        val arr = post("print2go-docks")?.optJSONArray("docks") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
    fun setMirrorFrom(dockId: String?): Boolean =
        post("mirror-from", JSONObject().put("dock_id", dockId ?: JSONObject.NULL))?.optBoolean("ok") == true

    private fun post(path: String, extra: JSONObject = JSONObject()): JSONObject? = try {
        val body = JSONObject().put("id", identity.deviceId).put("secret", identity.secret)
        for (k in extra.keys()) body.put(k, extra.get(k))
        val resp = client.newCall(Request.Builder()
            .url("${Prefs.cloudBase(context)}/api/device/$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()).execute()
        resp.use { JSONObject(it.body?.string() ?: "{}") }
    } catch (e: Exception) { DiagLog.log("post $path failed: ${e.message}"); null }

    private fun sendStatus(socket: WebSocket) {
        val reg = Registry(context)
        val sheets = JSONArray()
        for (id in reg.ids()) {
            val m = reg.model(id)
            sheets.put(JSONObject()
                .put("id", id).put("name", reg.name(id))
                .put("size", "${m.width}×${m.height} ${m.palette}").put("palette", m.palette))
        }
        socket.send(JSONObject()
            .put("t", "status").put("printer", Prefs.printerName(context)).put("state", "ready")
            .put("version", GO_VERSION).put("identifier", "touch")
            .put("jobs_today", Prefs.printedToday(context))
            .put("sheets", sheets).toString())
    }
}
