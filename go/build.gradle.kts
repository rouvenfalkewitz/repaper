plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    // reads google-services.json into the Firebase SDKs at build time
    id("com.google.gms.google-services") version "4.4.2" apply false
}
