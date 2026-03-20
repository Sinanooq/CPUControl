package com.cpucontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class WifiShareService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val CHANNEL_ID   = "wifi_share"
        const val NOTIF_ID     = 42

        // Bağlanan fragment'ın durumu okuyabilmesi için
        var isRunning = false
        var p2pInterface = ""
        var groupSsid = ""
        var groupPass  = ""
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var p2pChannel: WifiP2pManager.Channel

    override fun onCreate() {
        super.onCreate()
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        p2pChannel = p2pManager.initialize(this, mainLooper, null)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startShare()
            ACTION_STOP  -> stopShare()
        }
        return START_STICKY
    }

    private fun startShare() {
        startForeground(NOTIF_ID, buildNotification("WiFi Paylaşımı başlatılıyor..."))

        scope.launch {
            // Önce mevcut grubu temizle
            p2pManager.removeGroup(p2pChannel, object : ActionListener {
                override fun onSuccess() { createGroup() }
                override fun onFailure(r: Int) { createGroup() }
            })
        }
    }

    private fun createGroup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ → SSID ve şifre belirleyebiliyoruz
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-NS-CPUControl")
                .setPassphrase("cpucontrol123")
                .build()
            p2pManager.createGroup(p2pChannel, config, object : ActionListener {
                override fun onSuccess() { onGroupCreated() }
                override fun onFailure(r: Int) { updateNotification("Grup oluşturulamadı: $r") }
            })
        } else {
            p2pManager.createGroup(p2pChannel, object : ActionListener {
                override fun onSuccess() { onGroupCreated() }
                override fun onFailure(r: Int) { updateNotification("Grup oluşturulamadı: $r") }
            })
        }
    }

    private fun onGroupCreated() {
        p2pManager.requestGroupInfo(p2pChannel) { group ->
            if (group == null) {
                updateNotification("Grup bilgisi alınamadı")
                return@requestGroupInfo
            }
            groupSsid    = group.networkName ?: ""
            groupPass    = group.passphrase  ?: ""
            p2pInterface = group.`interface` ?: "p2p-wlan0-0"

            scope.launch {
                // 1. Interface'e IP ata (bazı cihazlarda otomatik atanmıyor)
                RootHelper.runAsRoot("ip addr add 192.168.49.1/24 dev $p2pInterface 2>/dev/null || true")
                RootHelper.runAsRoot("ip link set $p2pInterface up")

                // 2. DHCP server başlat (dnsmasq)
                RootHelper.startDnsmasq(p2pInterface)

                // 3. NAT kur
                val (ok, info) = RootHelper.startP2pNat(p2pInterface)
                isRunning = ok
                val msg = if (ok) "Aktif • $groupSsid • şifre: $groupPass"
                          else "NAT kurulamadı: $info"
                updateNotification(msg)
            }
        }
    }

    private fun stopShare() {
        scope.launch {
            RootHelper.stopDnsmasq()
            RootHelper.stopP2pNat(p2pInterface)
            isRunning = false
            p2pInterface = ""
            groupSsid = ""
            groupPass  = ""
        }
        p2pManager.removeGroup(p2pChannel, object : ActionListener {
            override fun onSuccess() {}
            override fun onFailure(r: Int) {}
        })
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Paylaşımı")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cpu)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
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
