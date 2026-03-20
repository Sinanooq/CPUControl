package com.cpucontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*

class WifiShareService : Service() {

    companion object {
        const val ACTION_START     = "START"
        const val ACTION_STOP      = "STOP"
        const val EXTRA_SSID       = "ssid"
        const val EXTRA_PASS       = "pass"
        const val CHANNEL_ID       = "wifi_share"
        const val NOTIF_ID         = 42
        const val BROADCAST_STATUS = "com.cpucontrol.WIFI_SHARE_STATUS"
        const val EXTRA_STATUS     = "status"
        const val EXTRA_MSG        = "msg"

        var isRunning  = false
        var groupSsid  = ""
        var groupPass  = ""
        var lastError  = ""
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var lbm: LocalBroadcastManager

    private var pendingSsid = ""
    private var pendingPass = ""

    override fun onCreate() {
        super.onCreate()
        lbm = LocalBroadcastManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pendingSsid = intent.getStringExtra(EXTRA_SSID) ?: "CPUControl"
                pendingPass = intent.getStringExtra(EXTRA_PASS) ?: "cpucontrol123"
                startShare()
            }
            ACTION_STOP -> stopShare()
        }
        return START_NOT_STICKY
    }

    private fun startShare() {
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."))
        scope.launch {
            updateNotification("Hotspot açılıyor...")
            broadcast("starting", "Hotspot açılıyor...")

            // 1) Mevcut hotspot/p2p varsa temizle
            RootHelper.runAsRoot("cmd wifi stop-softap 2>/dev/null || true")
            Thread.sleep(500)

            // 2) Hotspot başlat (root ile cmd wifi)
            val ssid = pendingSsid
            val pass = pendingPass
            val (ok, out) = RootHelper.startSoftAp(ssid, pass)

            if (!ok) {
                onError("Hotspot başlatılamadı: $out")
                return@launch
            }

            // 3) Hotspot interface'ini bul (ap0 veya wlan1 veya wlan0)
            Thread.sleep(1500)
            val apIface = RootHelper.getApInterface()
            if (apIface.isEmpty()) {
                onError("Hotspot arayüzü bulunamadı")
                return@launch
            }

            // 4) IP ata (bazı cihazlarda otomatik atanmaz)
            RootHelper.runAsRoot("ip addr add 192.168.43.1/24 dev $apIface 2>/dev/null || true")
            RootHelper.runAsRoot("ip link set $apIface up")

            // 5) dnsmasq başlat (DHCP)
            val dnsOk = RootHelper.startDnsmasq(apIface, "192.168.43")
            if (!dnsOk) {
                // dnsmasq yoksa Android'in kendi DHCP'si zaten çalışıyor olabilir, devam et
                updateNotification("dnsmasq bulunamadı, sistem DHCP kullanılıyor")
            }

            // 6) NAT / iptables
            val (natOk, natInfo) = RootHelper.startP2pNat(apIface)
            if (!natOk) {
                onError("NAT kurulamadı: $natInfo")
                return@launch
            }

            groupSsid = ssid
            groupPass = pass
            isRunning = true

            val msg = "Aktif • $ssid • şifre: $pass"
            updateNotification(msg)
            broadcast("running", msg)
        }
    }

    private fun stopShare() {
        scope.launch {
            RootHelper.stopDnsmasq()
            val apIface = RootHelper.getApInterface()
            if (apIface.isNotEmpty()) RootHelper.stopP2pNat(apIface)
            RootHelper.runAsRoot("cmd wifi stop-softap 2>/dev/null || true")
            isRunning = false
            groupSsid = ""
            groupPass = ""
            broadcast("stopped", "Durduruldu")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onError(msg: String) {
        isRunning = false
        lastError = msg
        updateNotification("Hata: $msg")
        broadcast("error", msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcast(status: String, msg: String) {
        lbm.sendBroadcast(Intent(BROADCAST_STATUS)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_MSG, msg))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Paylaşımı")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cpu)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "WiFi Paylaşımı", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
