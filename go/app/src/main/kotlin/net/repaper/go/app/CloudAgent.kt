package net.repaper.go.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
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
                        .put("claim", identity.claimCode).put("kind", "go")
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
            "mirror_job" -> {
                // a Dock Light somewhere printed a page for THIS device — fetch and spool it
                val job = msg.optJSONObject("job")
                if (job != null) scope.launch { fetchMirrorJob(job.optString("id"), job.optString("name", "job")) }
            }
            "diag" -> socket.send(JSONObject().put("t", "diag").put("log", DiagLog.dump()).toString())
        }
    }

    /** The main screen (when open) refreshes the moment a mirror job lands. */
    @Volatile var onJobArrived: (() -> Unit)? = null

    private fun fetchMirrorJob(id: String, name: String) {
        if (id.isEmpty()) return
        try {
            val body = JSONObject().put("id", identity.deviceId).put("secret", identity.secret)
            val resp = client.newCall(okhttp3.Request.Builder()
                .url("${Prefs.cloudBase(context)}/api/device/mirror-job/$id")
                .post(okhttp3.RequestBody.create(null, body.toString()))
                .header("Content-Type", "application/json")
                .build()).execute()
            val obj = resp.use { JSONObject(it.body?.string() ?: "{}") }
            if (!obj.optBoolean("ok")) { DiagLog.log("mirror job $id: fetch refused"); return }
            val bytes = android.util.Base64.decode(obj.optString("data"), android.util.Base64.DEFAULT)
            val ext = if (obj.optString("type") == "pdf") "pdf" else "png"
            JobStore(context).newJob(name, ext).writeBytes(bytes)
            DiagLog.log("mirror job spooled: $name (${bytes.size} B)")
            onJobArrived?.invoke()
        } catch (e: Exception) {
            DiagLog.log("mirror job $id failed: ${e.message}")
        }
    }

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
