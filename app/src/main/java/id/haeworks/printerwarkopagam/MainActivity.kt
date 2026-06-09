package id.haeworks.printerwarkopagam

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import id.haeworks.printerwarkopagam.ui.theme.PrinterWarkopAgamAjwaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrinterWarkopAgamAjwaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PrintProxyScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun PrintProxyScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val sharedPref = remember { context.getSharedPreferences("PrintProxyPrefs", Context.MODE_PRIVATE) }
    var showAutoStartDialog by remember {
        mutableStateOf(sharedPref.getBoolean("is_first_launch", true))
    }

    var isServerRunning by remember {
        mutableStateOf(isServiceRunning(context, PrintProxyService::class.java))
    }
    var tabletIpAddress by remember { mutableStateOf(getLocalIpAddress()) }

    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf(0) }
    var foundPrinters by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(lifecycleOwner.lifecycle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isServerRunning = isServiceRunning(context, PrintProxyService::class.java)
            tabletIpAddress = getLocalIpAddress()
        }
    }

    // Efek otomatisasi saat aplikasi dibuka atau kembali ke depan (Resume)
    LaunchedEffect(lifecycleOwner.lifecycle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 1. Cek status server terkini
            val runningStatus = isServiceRunning(context, PrintProxyService::class.java)
            isServerRunning = runningStatus
            tabletIpAddress = getLocalIpAddress()

            // 2. OTOMATISASI: Jika server ternyata MATI, langsung jalankan otomatis!
            if (!runningStatus) {
                val intent = Intent(context, PrintProxyService::class.java)
                context.startForegroundService(intent)
                isServerRunning = true
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Print Proxy Server",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )

            // --- SEKSI INFORMASI IP & PORT ---
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "IP Tablet Anda:", fontWeight = FontWeight.SemiBold)
                        Text(text = tabletIpAddress, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "Port Proxy:", fontWeight = FontWeight.SemiBold)
                        Text(text = "50213", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- SEKSI SCANNER PRINTER (FITUR BARU) ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Temukan Printer Thermal LAN/Wi-Fi", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isScanning) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isScanning = true
                                    scanProgress = 0
                                    foundPrinters = scanLocalPrinters(tabletIpAddress) { progress ->
                                        scanProgress = progress
                                    }
                                    isScanning = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SCAN PRINTER LOKAL")
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(progress = { scanProgress / 254f })
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "Memeriksa Jaringan: $scanProgress / 254 IP", fontSize = 12.sp)
                        }
                    }

                    // Tampilkan Hasil Scan jika ada printer terdeteksi
                    AnimatedVisibility(visible = foundPrinters.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Text(text = "Printer Ditemukan (Port 9100):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(8.dp))

                            // Menampilkan list IP Printer
                            foundPrinters.forEach { ip ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F7FA)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = "🖨️  $ip", fontWeight = FontWeight.Medium, color = Color(0xFF006064))
                                        Text(text = "Siap Pakai", fontSize = 12.sp, color = Color(0xFF00838F))
                                    }
                                }
                            }
                        }
                    }

                    if (foundPrinters.isEmpty() && !isScanning && scanProgress == 254) {
                        Text(text = "Tidak ada printer thermal LAN terdeteksi.", fontSize = 12.sp, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Indikator Status Proxy Server
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isServerRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).background(color = if (isServerRunning) Color(0xFF4CAF50) else Color(0xFFF44336), shape = androidx.compose.foundation.shape.CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (isServerRunning) "SERVER PROXY AKTIF" else "SERVER PROXY MATI", color = if (isServerRunning) Color(0xFF2E7D32) else Color(0xFFC62828), fontWeight = FontWeight.Medium)
                }
            }

            // Tombol Kontrol Proxy Server
            Button(
                onClick = {
                    val intent = Intent(context, PrintProxyService::class.java)
                    context.startForegroundService(intent)
                    isServerRunning = true
                },
                enabled = !isServerRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("START SERVER PROXY", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val intent = Intent(context, PrintProxyService::class.java)
                    context.stopService(intent)
                    isServerRunning = false
                },
                enabled = isServerRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("STOP SERVER PROXY", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (showAutoStartDialog) {
            AlertDialog(
                onDismissRequest = { /* Mencegah dialog ditutup tanpa aksi */ },
                title = { Text(text = "Aktifkan Auto-Start Layanan Printer Dapur") },
                text = {
                    Text("Agar printer proxy dapat langsung bekerja otomatis saat tablet dinyalakan (tanpa perlu membuka aplikasi ini manual di pagi hari), mohon aktifkan izin 'Auto Start' atau 'Mulai Otomatis' untuk aplikasi ini.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // Buka halaman pengaturan sistem
                            openAutoStartSettings(context)
                            // Simpan status agar dialog tidak muncul lagi di masa mendatang
                            sharedPref.edit().putBoolean("is_first_launch", false).apply()
                            showAutoStartDialog = false
                        }
                    ) {
                        Text("Buka Pengaturan")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        sharedPref.edit().putBoolean("is_first_launch", false).apply()
                        showAutoStartDialog = false
                    }) {
                        Text("Nanti Saja", color = Color.Gray)
                    }
                }
            )
        }

    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (networkInterface in interfaces) {
            val addresses = Collections.list(networkInterface.inetAddresses)
            for (address in addresses) {
                if (!address.isLoopbackAddress) {
                    val sAddr = address.hostAddress
                    val isIPv4 = sAddr.indexOf(':') < 0
                    if (isIPv4) {
                        return sAddr
                    }
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
    return "Tidak Tersambung Wi-Fi"
}


fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

suspend fun scanLocalPrinters(tabletIp: String, onProgress: (Int) -> Unit): List<String> = withContext(Dispatchers.IO) {
    val printerList = mutableListOf<String>()

    if (tabletIp == "Tidak Tersambung Wi-Fi" || !tabletIp.contains(".")) {
        return@withContext emptyList<String>()
    }

    val parts = tabletIp.split(".")
    if (parts.size != 4) return@withContext emptyList<String>()

    val prt = 9100

    // Pola Jaringan Kelas A / Enterprise (Misal: 10.x.x.x)
    // Kita akan scan 254 host di segmen ketiga dan keempat yang paling dekat dengan IP Tablet
    val baseSubnet = "${parts[0]}.${parts[1]}.${parts[2]}."

    val deferredScans = (1..254).map { host ->
        async {
            val targetIp = "$baseSubnet$host"
            if (targetIp == tabletIp) return@async null

            try {
                Socket().use { socket ->
                    // Timeout dinaikkan sedikit ke 500ms karena jaringan 10.x.x.x biasanya memiliki routing yang lebih panjang
                    socket.connect(InetSocketAddress(targetIp, prt), 500)
                    targetIp
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    deferredScans.awaitAll().forEachIndexed { index, ip ->
        onProgress(index + 1)
        if (ip != null) {
            printerList.add(ip)
        }
    }

    return@withContext printerList
}

fun openAutoStartSettings(context: Context) {
    val intents = arrayOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent().setComponent(ComponentName("com.htc.pitroad", "com.htc.pitroad.MainActivity")),
        Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"))
    )

    var openedSuccessfully = false
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            openedSuccessfully = true
            break
        } catch (e: Exception) {
            // Coba intent berikutnya jika intent saat ini tidak cocok dengan OS tablet
        }
    }

    // Jika merek OS tidak terdaftar di atas, lempar ke halaman detail aplikasi standar
    if (!openedSuccessfully) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}