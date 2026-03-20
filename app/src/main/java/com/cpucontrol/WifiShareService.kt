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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*

class WifiShareService : Service() {

    companion object {
        const val ACTION_START   = "START"
        const val ACTION_STOP    = "STOP"
        const val EXTRA_SSID     = "ssid"
        const val EXTRA_PASS     = "pass"
        const val CHANNEL_ID     = "wifi_share"
        const val NOTIF_ID       = 42
        const val BROADCAST_STATUS = "com.cpucontrol.WIFI_SHARE_STATUS"
        const val EXTRA_STATUS   = "status"   // "running", "stopped", "error"
        const val EXTRA_MSG      = "msg"

        var isRunning    = false
        var p2pInterface = ""
        var groupSsid    = ""
        var groupPass    = ""
    }

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var p2pChannel: WifiP2pManager.Channel
    private lateinit var lbm: LocalBroadcastManager

    private var pendingSsid = ""
    private var pendingPass = ""

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
                startShare()
            }
            ACTION_STOP -> stopShare()
        }
        return START_NOT_STICKY
    }

    private fun startShare() {
        startForeground(NOTIF_ID, buildNotification("Başlatılıyor..."))
        mainHandler.post {
            // Önce P2P'yi tamamen sıfırla
            p2pManager.cancelConnect(p2pChannel, object : ActionListener {
                override fun onSuccess() { removeGroupThenCreate() }
                override fun onFailure(r: Int) { removeGroupThenCreate() }
            })
        }
    }

    private fun removeGroupThenCreate() {
        p2pManager.removeGroup(p2pChannel, object : ActionListener {
            override fun onSuccess() { mainHandler.postDelayed({ createGroup() }, 800) }
            override fun onFailure(r: Int) { mainHandler.postDelayed({ createGroup() }, 800) }
        })
    }

    private fun createGroup() {
        updateNotification("WiFi Direct grubu oluşturuluyor...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ssid = if (pendingSsid.startsWith("DIRECT-")) pendingSsid else "DIRECT-${pendingSsid}"
            val config = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(pendingPass)
                .build()
            p2pManager.createGroup(p2pChannel, config, object : ActionListener {
                override fun onSuccess() { mainHandler.postDelayed({ fetchGroupInfo() }, 1500) }
                override fun onFailure(r: Int) {
                    // Özel config başarısız → varsayılan ile dene (SSID/şifre sistem tarafından atanır)
                    mainHandler.postDelayed({
                        p2pManager.createGroup(p2pChannel, object : ActionListener {
                            override fun onSuccess() { mainHandler.postDelayed({ fetchGroupInfo() }, 1500) }
                            override fun onFailure(r2: Int) { onError("Grup oluşturulamadı (kod $r2) — WiFi açık mı?") }
                        })
                    }, 1000)
                }
            })
        } else {
            p2pManager.createGroup(p2pChannel, object : ActionListener {
                override fun onSuccess() { mainHandler.postDelayed({ fetchGroupInfo() }, 1500) }
                override fun onFailure(r: Int) { onError("Grup oluşturulamadı (kod $r)") }
            })
        }
    }

    private fun fetchGroupInfo() {
        p2pManager.requestGroupInfo(p2pChannel) { group ->
            if (group == null) {
                // Bir kez daha dene
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
