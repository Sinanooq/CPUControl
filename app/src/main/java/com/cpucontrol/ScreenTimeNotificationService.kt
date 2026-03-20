package com.cpucontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ScreenTimeNotificationService : Service() {

    private val scope    = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val CHANNEL  = "battery_info_low"
    private val NOTIF_ID = 42

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif(null))
        startUpdating()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Servis görev listesinden kaldırılınca yeniden başlat
        val restart = Intent(applicationContext, ScreenTimeNotificationService::class.java)
        val pi = PendingIntent.getService(applicationContext, 1, restart, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, pi)
        super.onTaskRemoved(rootIntent)
    }

    private fun startUpdating() {
        scope.launch {
            while (true) {
                val data = withContext(Dispatchers.IO) {
                    ScreenTimeFragment.collectData(applicationContext)
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotif(data))
                delay(60_000)
            }
        }
    }

    private fun buildNotif(data: ScreenTimeFragment.ScreenData?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val bat = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Session yoksa minimal bildirim
        if (data == null || data.sessionElapsedMs == 0L) {
            return NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Ekran Süresi")
                .setContentText("$bat%  •  Şarj takılınca ölçüm başlar")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        }

        val period  = if (data.isChargePeriod) "Şarjdan bu yana" else "Son 24 saat"
        val subText = "$bat%  •  $period"

        val text = "📱 ${fmt(data.screenOnMs)}  🌙 ${fmt(data.screenOffMs)}  💤 ${fmt(data.deepSleepMs)}"

        val topApps = data.appUsages.take(3)
            .joinToString("\n") { "  • ${it.label.take(20)}: ${fmt(it.timeMs)}" }

        val bigText = buildString {
            append(text)
            append("\n👁 Uyanık: ${fmt(data.awakeMs)}")
            append("  ⏱ ${fmt(data.sessionElapsedMs)}")
            if (topApps.isNotEmpty()) append("\n\n$topApps")
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setSubText(subText)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0dk"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}s${m}dk" else "${m}dk"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Ekran Süresi", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
