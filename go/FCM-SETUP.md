# Android push (FCM) — remaining setup

Waking a **closed** RePaper Go Android app for a Print2Go job needs Firebase Cloud
Messaging. The **cloud side is already built** (`cloud/src/fcm.ts`, branched in `offerJob`,
env `FCM_SERVICE_ACCOUNT`) and the app already relays its token over the socket
(`CloudAgent.setPushToken` → `{t:push_token, env:"fcm"}`). What's left needs a Firebase
project and its config files, so it isn't wired into Gradle yet (adding the plugin without
`google-services.json` would break every Android build).

## 1. Firebase project (console.firebase.google.com)
- Create a project (or reuse one). Add an **Android app** with package **`net.repaper.go`**.
- Download **`google-services.json`** → put it at `go/app/google-services.json`.
- Project settings → **Service accounts → Generate new private key** → downloads a JSON.
  On the cloud, set `FCM_SERVICE_ACCOUNT` = `base64 -i <that-file>.json` in `.env`, then
  redeploy (see `cloud/DEPLOYMENT.md`).

## 2. Gradle
Project `go/build.gradle.kts` → `plugins { … }`:
```kotlin
id("com.google.gms.google-services") version "4.4.2" apply false
```
App `go/app/build.gradle.kts`:
```kotlin
plugins { /* … */ id("com.google.gms.google-services") }
dependencies { /* … */ implementation("com.google.firebase:firebase-messaging:24.0.3") }
```

## 3. The messaging service — `go/app/src/main/kotlin/net/repaper/go/app/PushService.kt`
```kotlin
package net.repaper.go.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives the FCM token + wake-up pushes for closed-app Print2Go jobs. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { CloudAgent.get(this).setPushToken(token) }

    override fun onMessageReceived(msg: RemoteMessage) {
        val n = msg.notification ?: return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel("print2go", "Print jobs", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        mgr.notify(1, NotificationCompat.Builder(this, "print2go")
            .setSmallIcon(R.drawable.ic_nfc).setContentTitle(n.title).setContentText(n.body)
            .setAutoCancel(true).setContentIntent(tap).build())
    }
}
```
Register it in `go/app/src/main/AndroidManifest.xml` inside `<application>`:
```xml
<service android:name=".PushService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT"/></intent-filter>
</service>
```

## 4. Fetch the token on start
In `MainActivity.onCreate` (after `CloudAgent.get(this).start()`):
```kotlin
com.google.firebase.messaging.FirebaseMessaging.getInstance().token
    .addOnSuccessListener { CloudAgent.get(this).setPushToken(it) }
```
`POST_NOTIFICATIONS` is already requested in `MainActivity.requestBlePermissions()`.

## 5. Verify
Close the app, forward a job from a Dock the phone mirrors → the cloud sees the phone
offline with a token and platform `android`, sends via FCM, and the banner opens the app.
