package id.haeworks.printerwarkopagam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast action: $action")

        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (action in validActions) {
            val serviceIntent = Intent(context, PrintProxyService::class.java)

            try {
                // Mulai service di background saat perangkat dinyalakan
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("BootReceiver", "PrintProxyService started successfully from $action")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start PrintProxyService from boot: ${e.message}", e)
            }
        }
    }
}