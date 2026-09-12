# Android push (FCM) — setup status

Waking a **closed** RePaper Go Android app for a Print2Go job needs Firebase Cloud
Messaging. Both sides are now wired:

- **Cloud:** `cloud/src/fcm.ts`, branched in `offerJob`, env `FCM_SERVICE_ACCOUNT`.
- **App:** relays its token over the socket (`CloudAgent.setPushToken` → `{t:push_token,
  env:"fcm"}`), fetched in `ShellActivity.onCreate` and on refresh via `PushService`.

Firebase project: **repaper-24a36** (package `net.repaper.go`).

## Done (app side)
- `go/app/google-services.json` in place (project repaper-24a36).
- Gradle: `com.google.gms.google-services` 4.4.2 (project), applied in `:app` with the
  Firebase BoM `34.19.0` + `firebase-messaging`.
- `PushService` (FirebaseMessagingService) registered in the manifest — receives the token
  (`onNewToken` → cloud) and wake-up pushes (`onMessageReceived` → high-priority banner that
  opens `ShellActivity`).
- Token also fetched once on launch in `ShellActivity`. `POST_NOTIFICATIONS` is requested in
  `ShellActivity.requestBlePermissions()`.

## Remaining (cloud side — needs Rouven + a deploy)
1. Firebase console → **Project settings → Service accounts → Generate new private key** →
   downloads a service-account JSON (keep it secret; store in 1Password like the APNs .p8).
2. On the cloud, set in `.env`:  `FCM_SERVICE_ACCOUNT` = `base64 -i <that-file>.json`
3. Redeploy the cloud (see `cloud/DEPLOYMENT.md` / the deploy memory).

## Verify (end-to-end, once the cloud key is set)
Close the app, forward a job from a Dock the phone mirrors → the cloud sees the phone
offline with a token and platform `android`, sends via FCM, and the banner opens the app on
the pending job.

> Note: a bare emulator without Google Play won't return an FCM token; use the
> `google_apis`/Play image or a real device to test token delivery.
