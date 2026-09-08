package net.repaper.go.app

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small rolling log the fleet console can request remotely ("Request diagnostics") —
 *  BLE phases, added-sheet configs, print results. Never log sheet keys. */
object DiagLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized fun log(line: String) {
        lines.addLast("${fmt.format(Date())} $line")
        while (lines.size > 300) lines.removeFirst()
    }

    @Synchronized fun dump(): String =
        "RePaper Go $GO_VERSION on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n" +
        lines.joinToString("\n")
}
