package net.repaper.go.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import net.repaper.go.R

/** Receives the FCM token + wake-up pushes for closed-app Print2Go jobs. The token is
 *  relayed to the cloud over the device socket; a push raises a banner that opens the app,
 *  where the pending job is taken and printed. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { CloudAgent.get(this).setPushToken(token) }

    override fun onMessageReceived(msg: RemoteMessage) {
        val n = msg.notification ?: return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("print2go", "Print jobs", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, ShellActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mgr.notify(1, NotificationCompat.Builder(this, "print2go")
            .setSmallIcon(R.drawable.ic_nfc)
            .setContentTitle(n.title).setContentText(n.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(tap).build())
    }
}
