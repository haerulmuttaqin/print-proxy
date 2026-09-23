package id.haeworks.printerwarkopagam

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class PrintProxyService : Service() {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val PORT = 50213
    private val CHANNEL_ID = "PrintProxyChannel"

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        createNotificationChannel()
        startForegroundService()
        startHttpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        if (!isRunning) {
            startHttpServer()
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PrintProxy::WakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }

            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "PrintProxy::WifiLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.e("PrintProxy", "Error acquiring WakeLock/WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("PrintProxy", "Error releasing WakeLock: ${e.message}")
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.e("PrintProxy", "Error releasing WifiLock: ${e.message}")
        }
    }

    private fun startHttpServer() {
        if (isRunning) return
        isRunning = true

        thread(name = "PrintProxyServerThread") {
            while (isRunning) {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(PORT))
                    }
                    Log.d("PrintProxy", "Server running and listening on port $PORT")

                    while (isRunning) {
                        val clientSocket = serverSocket?.accept() ?: break
                        thread { handleClient(clientSocket) }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e("PrintProxy", "Server socket exception: ${e.message}. Re-binding in 2 seconds...")
                        try {
                            serverSocket?.close()
                        } catch (_: Exception) {}
                        try {
                            Thread.sleep(2000)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10000 // 10s timeout agar koneksi gantung tidak membocorkan resource
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val outputStream = client.getOutputStream()

            // Baca HTTP Request Header & Body
            var line: String?
            var contentLength = 0
            val headers = StringBuilder()

            while (reader.readLine().also { line = it } != null) {
                if (line?.isEmpty() == true) break // Header selesai
                headers.append(line).append("\n")
                if (line?.startsWith("Content-Length:", ignoreCase = true) == true) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // Membaca isi Body JSON berdasarkan Content-Length secara utuh
            var requestBody = ""
            if (contentLength > 0) {
                val body = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = reader.read(body, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                requestBody = String(body, 0, totalRead)
            }

            // Logika CORS & Routing
            if (headers.contains("OPTIONS /print")) {
                sendResponse(outputStream, 200, "")
                return
            }

            if (headers.contains("POST /print")) {
                val json = JSONObject(requestBody)
                val ip = json.getString("ip")
                val base64Data = json.getString("base64Data")
                val port = json.optInt("port", 9100)

                val printerBytes = Base64.decode(base64Data, Base64.DEFAULT)

                // Kirim langsung ke Printer Thermal via Socket TCP
                Socket().use { printerSocket ->
                    printerSocket.connect(InetSocketAddress(ip, port), 5000)
                    printerSocket.getOutputStream().use { printerOs ->
                        printerOs.write(printerBytes)
                        printerOs.flush()
                    }
                }
                sendResponse(outputStream, 200, "{\"success\":true}")
            } else {
                sendResponse(outputStream, 404, "{\"error\":\"Not Found\"}")
            }

        } catch (e: Exception) {
            try {
                sendResponse(client.getOutputStream(), 500, "{\"error\":\"${e.message}\"}")
            } catch (_: Exception) {}
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(os: OutputStream, statusCode: Int, jsonResponse: String) {
        val statusText = when (statusCode) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            else -> "500 Internal Server Error"
        }

        val responseBytes = jsonResponse.toByteArray()
        val rawResponse = ("HTTP/1.1 $statusText\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "\r\n").toByteArray()

        os.write(rawResponse)
        if (responseBytes.isNotEmpty()) {
            os.write(responseBytes)
        }
        os.flush()
        os.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Print Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan latar belakang untuk meneruskan cetakan ke printer kasir/dapur"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kasir Print Proxy Aktif")
            .setContentText("Listening on port $PORT (Background Ready)")
            .setSmallIcon(R.drawable.baseline_print_24)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("PrintProxy", "onTaskRemoved called. Rescheduling service restart...")
        try {
            val restartIntent = Intent(applicationContext, PrintProxyService::class.java).apply {
                setPackage(packageName)
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                101,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                restartPendingIntent
            )
        } catch (e: Exception) {
            Log.e("PrintProxy", "Error onTaskRemoved: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        releaseLocks()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}