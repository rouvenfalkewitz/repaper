package net.repaper.go.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.print.PrintAttributes
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import net.repaper.go.R

/** "RePaper Go" in every app's print dialog — the Android twin of the Dock's AirPrint printer.
 *  A job is accepted, spooled as its PDF, and finishes immediately; picking the sheet happens
 *  in the app (same as walking to the Dock and tapping), announced by a notification. */
class GoPrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            val id = generatePrinterId("repaper-go")
            val caps = PrinterCapabilitiesInfo.Builder(id)
                // Sheets are small; offer label-ish sizes plus A6 so any app can print.
                .addMediaSize(PrintAttributes.MediaSize("repaper-label", "Label 2.9″", 2360, 1160), true)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A6, false)
                .addResolution(PrintAttributes.Resolution("default", "Default", 300, 300), true)
                .setColorModes(PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_COLOR)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            addPrinters(listOf(
                PrinterInfo.Builder(id, Prefs.printerName(this@GoPrintService), PrinterInfo.STATUS_IDLE)
                    .setCapabilities(caps).build()))
        }
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }

    override fun onPrintJobQueued(job: PrintJob) {
        job.start()
        val label = job.document.info.name ?: "print job"
        try {
            val out = JobStore(this).newJob(label.toString())
            val pfd = job.document.data ?: throw IllegalStateException("job has no document")
            pfd.use {
                java.io.FileInputStream(it.fileDescriptor).use { input ->
                    out.outputStream().use { o -> input.copyTo(o) }
                }
            }
            job.complete()
            notifyJob(label.toString())
        } catch (e: Exception) {
            job.fail(e.message ?: "could not spool the job")
        }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) { job.cancel() }

    private fun notifyJob(label: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("jobs", "Print jobs", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(this, "jobs")
            .setSmallIcon(R.drawable.ic_repaper)
            .setContentTitle("“$label” is waiting for a sheet")
            .setContentText("Open RePaper Go and choose which sheet to print it on.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
