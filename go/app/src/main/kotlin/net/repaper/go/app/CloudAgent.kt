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
                org = msg.optString("org").ifEmpty { null }
                Prefs.setClaimed(context, claimed)   // fleet removed us → the sign-in gate returns
            }
            "claimed" -> { claimed = true; org = msg.optString("org").ifEmpty { null }; Prefs.setClaimed(context, true); sendStatus(socket) }
            "identify" -> {} // a phone has no LED ring; the app could vibrate later
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
            .put("jobs_today", JobStore(context).list().size)
            .put("sheets", sheets).toString())
    }
}
