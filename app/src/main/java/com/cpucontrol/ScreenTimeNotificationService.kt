package com.cpucontrol

import android.app.*
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ScreenTimeNotificationService : Service() {

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val CHANNEL = "kernelkit_screen"
    private val ID      = 42

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(ID, buildNotif(null))
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Güncelleme döngüsü ──────────────────────────────────────────────────
    private fun startLoop() {
        scope.launch {
            while (isActive) {
                val data = ScreenTimeFragment.collectData(applicationContext)
                val notif = buildNotif(data)
                getSystemService(NotificationManager::class.java).notify(ID, notif)
                delay(30_000)
            }
        }
    }

    // ── Bildirim oluştur ────────────────────────────────────────────────────
    private fun buildNotif(data: ScreenTimeFragment.ScreenData?): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val bat = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return if (data == null || data.sessionElapsedMs == 0L) {
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("KernelKit  •  $bat%")
                .setContentText("Şarj takılınca ölçüm başlar")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        } else {
            val line1 = "📱 ${fmt(data.screenOnMs)}  🌙 ${fmt(data.screenOffMs)}  💤 ${fmt(data.deepSleepMs)}"
            val period = if (data.isChargePeriod) "Şarjdan bu yana" else "Son oturum"
            val bigText = buildString {
                appendLine("📱 Ekran Açık:   ${fmt(data.screenOnMs)}")
                appendLine("🌙 Ekran Kapalı: ${fmt(data.screenOffMs)}")
                appendLine("👁 Uyanık:       ${fmt(data.awakeMs)}")
                appendLine("💤 Deep Sleep:   ${fmt(data.deepSleepMs)}")
                append("⏱ Toplam:        ${fmt(data.sessionElapsedMs)}")
                val top = data.appUsages.take(3)
                if (top.isNotEmpty()) {
                    append("\n\n")
                    append(top.joinToString("\n") { "  • ${it.label.take(22)}: ${fmt(it.timeMs)}" })
                }
            }
            NotificationCompat.Builder(this, CHANNEL)
                .setSubText("$bat%  •  $period")
                .setContentText(line1)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Ekran Süresi", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0dk"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}s${m}dk" else "${m}dk"
    }
}
