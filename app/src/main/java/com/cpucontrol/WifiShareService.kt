package com.cpucontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ActionListener
import android.os.Build
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

        var isRunning    = false
        var p2pInterface = ""
        var groupSsid    = ""
        var groupPass    = ""
        var lastError    = ""
    }

    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var p2pChannel: WifiP2pManager.Channel
    private lateinit var lbm: LocalBroadcastManager

    private var pendingSsid = ""
    private var pendingPass = ""
    private var retryCount  = 0

    override fun onCreate() {
        super.onCreate()
        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        p2pChannel = p2pManager.initialize(this, mainLooper, null)
        lbm = LocalBroadcastManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pendingSsid = intent.getStringExtra(EXTRA_SSID) ?: "CPUControl"
                pendingPass = intent.getStringExtra(EXTRA_PASS) ?: "cpucontrol123"
                retryCount  = 0
                startShare()
            }
            ACTION_STOP -> stopShare()
        }
        return START_NOT_STICKY
    }

    private fun startShare() {
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."))
        scope.launch {
            updateNotification("P2P grubu oluşturuluyor...")
            // Önce root/wpa_cli ile dene (en güvenilir yol)
            val (wpaOk, wpaIface) = RootHelper.wpaCliCreateGroup(pendingSsid, pendingPass)
            if (wpaOk && wpaIface.isNotEmpty()) {
                // wpa_cli başarılı — SSID/şifreyi wpa_cli'den oku
                val (_, ssidOut) = RootHelper.runAsRoot("wpa_cli -i ${RootHelper.getWlanInterface()} p2p_group_status | grep ssid | head -1")
                val (_, passOut) = RootHelper.runAsRoot("wpa_cli -i ${RootHelper.getWlanInterface()} p2p_group_status | grep psk | head -1")
                val ssid = ssidOut.substringAfter("=").trim().ifEmpty { "DIRECT-${pendingSsid}" }
                val pass = passOut.substringAfter("=").trim().ifEmpty { pendingPass }
                applyNat(ssid, pass, wpaIface)
            } else {
                // wpa_cli yoksa WifiP2pManager API'sine düş
                mainHandler.post { initChannelAndCreate() }
            }
        }
    }

    private fun initChannelAndCreate() {
        try { p2pChannel.close() } catch (_: Exception) {}
        p2pChannel = p2pManager.initialize(this, mainLooper, null)
        mainHandler.postDelayed({ removeAndCreate() }, 300)
    }

    private fun removeAndCreate() {
        p2pManager.removeGroup(p2pChannel, object : ActionListener {
            override fun onSuccess() { mainHandler.postDelayed({ createGroup() }, 600) }
            override fun onFailure(r: Int) { mainHandler.postDelayed({ createGroup() }, 600) }
        })
    }

    private fun createGroup() {
        updateNotification("WiFi Direct grubu oluşturuluyor...")

        val listener = object : ActionListener {
            override fun onSuccess() {
                mainHandler.postDelayed({ fetchGroupInfo() }, 1500)
            }
            override fun onFailure(r: Int) {
                if (retryCount < 2) {
                    retryCount++
                    updateNotification("Yeniden deneniyor ($retryCount/2)...")
                    // Channel'ı yenile ve tekrar dene
                    mainHandler.postDelayed({ initChannelAndCreate() }, 1500)
                } else {
                    onError("Grup oluşturulamadı (kod $r) — WiFi Direct destekleniyor mu?")
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ssid = if (pendingSsid.startsWith("DIRECT-")) pendingSsid else "DIRECT-${pendingSsid}"
            try {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(pendingPass)
                    .build()
                p2pManager.createGroup(p2pChannel, config, listener)
            } catch (e: Exception) {
                // Config oluşturulamadıysa varsayılan ile dene
                p2pManager.createGroup(p2pChannel, listener)
            }
        } else {
            p2pManager.createGroup(p2pChannel, listener)
        }
    }

    private fun fetchGroupInfo() {
        p2pManager.requestGroupInfo(p2pChannel) { group ->
            if (group == null) {
                mainHandler.postDelayed({
                    p2pManager.requestGroupInfo(p2pChannel) { g2 ->
                        if (g2 == null) onError("Grup bilgisi alınamadı")
                        else applyNat(g2.networkName ?: pendingSsid, g2.passphrase ?: pendingPass, g2.`interface` ?: "p2p-wlan0-0")
                    }
                }, 1500)
            } else {
                applyNat(group.networkName ?: pendingSsid, group.passphrase ?: pendingPass, group.`interface` ?: "p2p-wlan0-0")
            }
        }
    }

    private fun applyNat(ssid: String, pass: String, iface: String) {
        groupSsid    = ssid
        groupPass    = pass
        p2pInterface = iface

        scope.launch {
            RootHelper.runAsRoot("ip addr add 192.168.49.1/24 dev $iface 2>/dev/null || true")
            RootHelper.runAsRoot("ip link set $iface up")
            RootHelper.startDnsmasq(iface)
            val (ok, info) = RootHelper.startP2pNat(iface)
            isRunning = ok
            if (ok) {
                val msg = "Aktif • $ssid • şifre: $pass"
                updateNotification(msg)
                broadcast("running", msg)
            } else {
                onError("NAT kurulamadı: $info")
            }
        }
    }

    private fun onError(msg: String) {
        isRunning = false
        lastError = msg
        updateNotification("Hata: $msg")
        broadcast("error", msg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopShare() {
        scope.launch {
            RootHelper.stopDnsmasq()
            if (p2pInterface.isNotEmpty()) RootHelper.stopP2pNat(p2pInterface)
            isRunning    = false
            p2pInterface = ""
            groupSsid    = ""
            groupPass    = ""
            broadcast("stopped", "Durduruldu")
        }
        mainHandler.post {
            p2pManager.removeGroup(p2pChannel, object : ActionListener {
                override fun onSuccess() {}
                override fun onFailure(r: Int) {}
            })
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcast(status: String, msg: String) {
        lbm.sendBroadcast(Intent(BROADCAST_STATUS).putExtra(EXTRA_STATUS, status).putExtra(EXTRA_MSG, msg))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Paylaşımı")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cpu)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
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
