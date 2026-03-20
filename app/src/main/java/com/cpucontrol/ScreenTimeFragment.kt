package com.cpucontrol

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class ScreenTimeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_screen_time, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val btnGrant       = view.findViewById<MaterialButton>(R.id.btnGrantUsage)
        val btnResetCharge = view.findViewById<MaterialButton>(R.id.btnResetCharge)
        val btnToggleNotif = view.findViewById<MaterialButton>(R.id.btnToggleNotif)
        val layoutPerm     = view.findViewById<View>(R.id.layoutPermission)
        val layoutContent  = view.findViewById<View>(R.id.layoutContent)

        btnGrant.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnResetCharge.setOnClickListener {
            resetSession()
            loadData(view)
        }

        val prefs = requireContext().getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)

        fun updateNotifBtn() {
            val on = prefs.getBoolean("screen_time_notif", false)
            btnToggleNotif.text = if (on) "🔔" else "🔕"
            btnToggleNotif.backgroundTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getColor(if (on) R.color.accent_cyan else R.color.bg_elevated)
            )
        }
        updateNotifBtn()
        btnToggleNotif.setOnClickListener {
            val on = !prefs.getBoolean("screen_time_notif", false)
            prefs.edit().putBoolean("screen_time_notif", on).apply()
            val svc = Intent(requireContext(), ScreenTimeNotificationService::class.java)
            if (on) requireContext().startForegroundService(svc)
            else requireContext().stopService(svc)
            updateNotifBtn()
        }

        if (!hasUsagePermission()) {
            layoutPerm.visibility    = View.VISIBLE
            layoutContent.visibility = View.GONE
        } else {
            layoutPerm.visibility    = View.GONE
            layoutContent.visibility = View.VISIBLE
            loadData(view)
        }
    }

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        if (hasUsagePermission()) {
            v.findViewById<View>(R.id.layoutPermission).visibility = View.GONE
            v.findViewById<View>(R.id.layoutContent).visibility    = View.VISIBLE
            loadData(v)
        }
    }

    private fun resetSession() {
        val now = System.currentTimeMillis()
        requireContext().getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE).edit()
            .putLong("session_start_wall",    now)
            .putLong("session_start_elapsed", SystemClock.elapsedRealtime())
            .putLong("session_start_uptime",  SystemClock.uptimeMillis())
            .apply()
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = requireContext().getSystemService(AppOpsManager::class.java)
        val mode   = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            requireContext().packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadData(view: View) {
        val tvScreenOnVal  = view.findViewById<TextView>(R.id.tvScreenOnVal)
        val tvScreenOnPct  = view.findViewById<TextView>(R.id.tvScreenOnPct)
        val tvScreenOffVal = view.findViewById<TextView>(R.id.tvScreenOffVal)
        val tvScreenOffPct = view.findViewById<TextView>(R.id.tvScreenOffPct)
        val tvDeepSleepVal = view.findViewById<TextView>(R.id.tvDeepSleepVal)
        val tvDeepSleepPct = view.findViewById<TextView>(R.id.tvDeepSleepPct)
        val tvAwakeVal     = view.findViewById<TextView>(R.id.tvAwakeVal)
        val tvAwakePct     = view.findViewById<TextView>(R.id.tvAwakePct)
        val tvUptimeVal    = view.findViewById<TextView>(R.id.tvUptimeVal)
        val tvPeriodLabel  = view.findViewById<TextView>(R.id.tvPeriodLabel)
        val tvAppLabel     = view.findViewById<TextView>(R.id.tvAppLabel)
        val listApps       = view.findViewById<LinearLayout>(R.id.listAppUsage)

        scope.launch {
            val data = withContext(Dispatchers.IO) { collectData() }

            // Battery Guru mantığı: total = screenOn + screenOff (deep sleep screenOff içinde)
            val total = (data.screenOnMs + data.screenOffMs).coerceAtLeast(1L)

            tvPeriodLabel.text = if (data.isChargePeriod) "ŞARJDAN BU YANA" else "SON 24 SAAT"
            tvAppLabel.text    = if (data.isChargePeriod) "UYGULAMA KULLANIMI (Şarjdan Bu Yana)" else "UYGULAMA KULLANIMI (Son 24 Saat)"

            tvScreenOnVal.text  = fmt(data.screenOnMs)
            tvScreenOnPct.text  = "${data.screenOnMs * 100 / total}%"
            tvScreenOffVal.text = fmt(data.screenOffMs)
            tvScreenOffPct.text = "${data.screenOffMs * 100 / total}%"

            // deep_sleep = elapsedRealtime - uptimeMillis (session başından delta)
            tvDeepSleepVal.text = fmt(data.deepSleepMs)
            tvDeepSleepPct.text = "${data.deepSleepMs * 100 / total}%"

            // awake = elapsed - deep_sleep (CPU uyanık, ekran kapalı)
            tvAwakeVal.text = fmt(data.awakeMs)
            tvAwakePct.text = "${data.awakeMs * 100 / total}%"

            tvUptimeVal.text = fmt(data.sessionElapsedMs)

            listApps.removeAllViews()
            val inflater = LayoutInflater.from(requireContext())
            data.appUsages.take(15).forEach { app ->
                val row = inflater.inflate(R.layout.item_app_usage, listApps, false)
                row.findViewById<TextView>(R.id.tvAppName).text = app.label
                row.findViewById<TextView>(R.id.tvAppTime).text = fmt(app.timeMs)
                val bar = row.findViewById<View>(R.id.viewUsageBar)
                val maxMs = data.appUsages.firstOrNull()?.timeMs ?: 1L
                bar.post {
                    val parentWidth = (bar.parent as View).width
                    val ratio = app.timeMs.toFloat() / maxMs.toFloat()
                    bar.layoutParams = bar.layoutParams.also {
                        it.width = (parentWidth * ratio).toInt().coerceAtLeast(4)
                    }
                    bar.requestLayout()
                }
                listApps.addView(row)
            }
        }
    }

    internal fun collectData(): ScreenData {
        val ctx = requireContext()
        val now = System.currentTimeMillis()
        val p   = ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)

        // Session başlangıcı: şarj takıldığında veya manuel sıfırlamada kaydedilir
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

        // Session süresi (elapsed)
        val sessionElapsedMs = if (sessionStartElapsed > 0L)
            SystemClock.elapsedRealtime() - sessionStartElapsed
        else
            SystemClock.elapsedRealtime()

        // Deep sleep = elapsedRealtime delta - uptimeMillis delta (Battery Guru mantığı)
        val deepSleepMs: Long = if (sessionStartElapsed > 0L && sessionStartUptime > 0L) {
            val elapsedDelta = SystemClock.elapsedRealtime() - sessionStartElapsed
            val uptimeDelta  = SystemClock.uptimeMillis()    - sessionStartUptime
            (elapsedDelta - uptimeDelta).coerceAtLeast(0L)
        } else {
            (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        }

        // UsageEvents ile ekran açık/kapalı süre hesapla
        val usm    = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(periodStart, now)
        var screenOnMs  = 0L
        var screenOffMs = 0L
        // Başlangıçta ekranın açık mı kapalı mı olduğunu bilmiyoruz
        // -1 = henüz ilk event gelmedi (belirsiz)
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
                    // Ekran açıldı
                    if (!firstEventSeen) {
                        // İlk event SCREEN_ON ise: başlangıçtan bu yana kapalıydı
                        lastOffTime = periodStart
                        firstEventSeen = true
                    }
                    if (lastOffTime >= 0L) {
                        screenOffMs += event.timeStamp - lastOffTime
                        lastOffTime = -1L
                    }
                    lastOnTime = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Ekran kapandı
                    if (!firstEventSeen) {
                        // İlk event SCREEN_OFF ise: başlangıçtan bu yana açıktı
                        lastOnTime = periodStart
                        firstEventSeen = true
                    }
                    if (lastOnTime >= 0L) {
                        screenOnMs += event.timeStamp - lastOnTime
                        lastOnTime = -1L
                    }
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
        // Hâlâ açıksa şimdiye kadar say
        if (lastOnTime >= 0L) screenOnMs  += now - lastOnTime
        if (lastOffTime >= 0L && lastOnTime < 0L) screenOffMs += now - lastOffTime
        // Hâlâ foreground'da olan uygulamalar
        appFgStart.forEach { (pkg, start) ->
            appFgTotal[pkg] = (appFgTotal[pkg] ?: 0L) + (now - start)
        }

        // awake = session elapsed - deep sleep (CPU uyanık ama ekran kapalı)
        val awakeMs = (sessionElapsedMs - deepSleepMs).coerceAtLeast(0L)

        val pm = ctx.packageManager
        val appUsages = appFgTotal.filter { (pkg, ms) ->
            if (ms <= 0) return@filter false
            // Sistem uygulamalarını ve launcher'ı filtrele
            val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return@filter false }
            val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            !isSystem
        }.map { (pkg, ms) ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        catch (_: Exception) { pkg }
            AppUsage(label, ms)
        }.sortedByDescending { it.timeMs }

        return ScreenData(
            sessionElapsedMs = sessionElapsedMs,
            deepSleepMs      = deepSleepMs,
            awakeMs          = awakeMs,
            screenOnMs       = screenOnMs,
            screenOffMs      = screenOffMs,
            appUsages        = appUsages,
            isChargePeriod   = isChargePeriod
        )
    }

    internal fun fmt(ms: Long): String {
        if (ms <= 0) return "0dk"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0  -> "${h}s ${m}dk"
            m > 0  -> "${m}dk ${s}sn"
            else   -> "${s}sn"
        }
    }

    data class AppUsage(val label: String, val timeMs: Long)
    data class ScreenData(
        val sessionElapsedMs: Long,
        val deepSleepMs:      Long,
        val awakeMs:          Long,
        val screenOnMs:       Long,
        val screenOffMs:      Long,
        val appUsages:        List<AppUsage>,
        val isChargePeriod:   Boolean
    )

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
