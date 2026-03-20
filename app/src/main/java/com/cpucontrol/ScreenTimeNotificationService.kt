package com.cpucontrol

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Battery Guru mantığıyla çalışan kalıcı ekran süresi bildirimi.
 *
 * Kanal: battery_info_low (IMPORTANCE_LOW, silent, ongoing)
 * İçerik:
 *   subText  → pil% • dönem
 *   text     → 📱 screenOn  🌙 screenOff  💤 deepSleep
 *   bigText  → + awake + top 3 uygulama
 */
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

    private fun startUpdating() {
        scope.launch {
            while (true) {
                val data = withContext(Dispatchers.IO) { collect() }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotif(data))
                delay(60_000)
            }
        }
    }

    private fun buildNotif(data: ScreenData?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val bat = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val period  = if (data?.isChargePeriod == true) "Şarjdan bu yana" else "Son 24 saat"
        val subText = "$bat%  •  $period"

        val text = if (data != null)
            "📱 ${fmt(data.screenOnMs)}  🌙 ${fmt(data.screenOffMs)}  💤 ${fmt(data.deepSleepMs)}"
        else "Yükleniyor..."

        val topApps = data?.appUsages?.take(3)
            ?.joinToString("\n") { "  • ${it.label.take(20)}: ${fmt(it.timeMs)}" } ?: ""

        val bigText = buildString {
            append(text)
            append("\n👁 Uyanık: ${fmt(data?.awakeMs ?: 0L)}")
            append("  ⏱ ${fmt(data?.sessionElapsedMs ?: 0L)}")
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun collect(): ScreenData {
        val ctx = this
        val now = System.currentTimeMillis()
        val p   = ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)

        val sessionStartWall    = p.getLong("session_start_wall",    0L)
        val sessionStartElapsed = p.getLong("session_start_elapsed", 0L)
        val sessionStartUptime  = p.getLong("session_start_uptime",  0L)

        val periodStart: Long
        val isChargePeriod: Boolean
        if (sessionStartWall > 0L && sessionStartWall < now) {
            periodStart    = sessionStartWall
            isChargePeriod = true
        } else {
            periodStart    = now - TimeUnit.HOURS.toMillis(24)
            isChargePeriod = false
        }

        val sessionElapsedMs = if (sessionStartElapsed > 0L)
            SystemClock.elapsedRealtime() - sessionStartElapsed
        else SystemClock.elapsedRealtime()

        val deepSleepMs: Long = if (sessionStartElapsed > 0L && sessionStartUptime > 0L) {
            val ed = SystemClock.elapsedRealtime() - sessionStartElapsed
            val ud = SystemClock.uptimeMillis()    - sessionStartUptime
            (ed - ud).coerceAtLeast(0L)
        } else {
            (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        }

        val usm    = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(periodStart, now)
        var screenOnMs  = 0L
        var screenOffMs = 0L
        var lastOnTime  = -1L
        var lastOffTime = -1L
        var firstEventSeen = false
        val appFgStart  = mutableMapOf<String, Long>()
        val appFgTotal  = mutableMapOf<String, Long>()
        val event       = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (!firstEventSeen) { lastOffTime = periodStart; firstEventSeen = true }
                    if (lastOffTime >= 0L) { screenOffMs += event.timeStamp - lastOffTime; lastOffTime = -1L }
                    lastOnTime = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (!firstEventSeen) { lastOnTime = periodStart; firstEventSeen = true }
                    if (lastOnTime >= 0L) { screenOnMs += event.timeStamp - lastOnTime; lastOnTime = -1L }
                    lastOffTime = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    appFgStart[event.packageName] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = appFgStart.remove(event.packageName) ?: continue
                    appFgTotal[event.packageName] = (appFgTotal[event.packageName] ?: 0L) + (event.timeStamp - start)
                }
            }
        }
        if (lastOnTime >= 0L) screenOnMs += now - lastOnTime
        if (lastOffTime >= 0L && lastOnTime < 0L) screenOffMs += now - lastOffTime
        appFgStart.forEach { (pkg, start) ->
            appFgTotal[pkg] = (appFgTotal[pkg] ?: 0L) + (now - start)
        }

        val awakeMs = (sessionElapsedMs - deepSleepMs).coerceAtLeast(0L)

        val pm = ctx.packageManager
        val appUsages = appFgTotal.filter { (pkg, ms) ->
            if (ms <= 0) return@filter false
            val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return@filter false }
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            !isSystem
        }.map { (pkg, ms) ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        catch (_: Exception) { pkg }
            ScreenTimeFragment.AppUsage(label, ms)
        }.sortedByDescending { it.timeMs }

        return ScreenData(sessionElapsedMs, deepSleepMs, awakeMs, screenOnMs, screenOffMs, appUsages, isChargePeriod)
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

    data class ScreenData(
        val sessionElapsedMs: Long,
        val deepSleepMs:      Long,
        val awakeMs:          Long,
        val screenOnMs:       Long,
        val screenOffMs:      Long,
        val appUsages:        List<ScreenTimeFragment.AppUsage>,
        val isChargePeriod:   Boolean
    )
}
