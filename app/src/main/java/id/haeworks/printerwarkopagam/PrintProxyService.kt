package id.haeworks.printerwarkopagam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class PrintProxyService : Service() {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val PORT = 50213
    private val CHANNEL_ID = "PrintProxyChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        startHttpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startHttpServer() {
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d("PrintProxy", "Server running on port $PORT")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    thread { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                Log.e("PrintProxy", "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
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
                    contentLength = line.substring(15).trim().toInt()
                }
            }

            // Membaca isi Body JSON berdasarkan Content-Length
            val body = CharArray(contentLength)
            reader.read(body, 0, contentLength)
            val requestBody = String(body)

            // Logika CORS & Routing (Meniru Node.js)
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
                    printerSocket.connect(java.net.InetSocketAddress(ip, port), 5000)
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
            } catch (_: Exception) {
            }
        } finally {
            client.close()
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
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kasir Print Proxy Aktif")
            .setContentText("Listening on port $PORT...")
            .setSmallIcon(R.drawable.baseline_print_24)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serverSocket?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}