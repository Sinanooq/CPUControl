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
        val layoutPerm     = view.findViewById<View>(R.id.layoutPermission)
        val layoutContent  = view.findViewById<View>(R.id.layoutContent)

        btnGrant.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnResetCharge.setOnClickListener {
            resetSession(requireContext())
            loadData(view)
        }

        if (!hasUsagePermission()) {
            layoutPerm.visibility    = View.VISIBLE
            layoutContent.visibility = View.GONE
        } else {
            layoutPerm.visibility    = View.GONE
            layoutContent.visibility = View.VISIBLE
            autoStartNotifService()
            loadData(view)
        }
    }

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        if (hasUsagePermission()) {
            v.findViewById<View>(R.id.layoutPermission).visibility = View.GONE
            v.findViewById<View>(R.id.layoutContent).visibility    = View.VISIBLE
            autoStartNotifService()
            loadData(v)
        }
    }

    /** Bildirim izni varsa servisi otomatik başlat */
    private fun autoStartNotifService() {
        val ctx = requireContext()
        val hasNotifPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        if (hasNotifPerm) {
            ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("screen_time_notif", true).apply()
            ctx.startForegroundService(Intent(ctx, ScreenTimeNotificationService::class.java))
        }
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
            val data = withContext(Dispatchers.IO) { collectData(requireContext()) }

            // Session yoksa sıfırla butonu bekle
            if (data.sessionElapsedMs == 0L) {
                tvPeriodLabel.text  = "HENÜZ ÖLÇÜM YOK"
                tvScreenOnVal.text  = "—"; tvScreenOnPct.text  = "—"
                tvScreenOffVal.text = "—"; tvScreenOffPct.text = "—"
                tvDeepSleepVal.text = "—"; tvDeepSleepPct.text = "—"
                tvAwakeVal.text     = "—"; tvAwakePct.text     = "—"
                tvUptimeVal.text    = "—"
                tvAppLabel.text     = "UYGULAMA KULLANIMI"
                listApps.removeAllViews()
                return@launch
            }

            val total = (data.screenOnMs + data.screenOffMs).coerceAtLeast(1L)

            tvPeriodLabel.text = if (data.isChargePeriod) "ŞARJDAN BU YANA" else "SON 24 SAAT"
            tvAppLabel.text    = if (data.isChargePeriod) "UYGULAMA KULLANIMI (Şarjdan Bu Yana)" else "UYGULAMA KULLANIMI (Son 24 Saat)"

            tvScreenOnVal.text  = fmt(data.screenOnMs)
            tvScreenOnPct.text  = "${data.screenOnMs * 100 / total}%"
            tvScreenOffVal.text = fmt(data.screenOffMs)
            tvScreenOffPct.text = "${data.screenOffMs * 100 / total}%"
            tvDeepSleepVal.text = fmt(data.deepSleepMs)
            tvDeepSleepPct.text = "${data.deepSleepMs * 100 / total}%"
            tvAwakeVal.text     = fmt(data.awakeMs)
            tvAwakePct.text     = "${data.awakeMs * 100 / total}%"
            tvUptimeVal.text    = fmt(data.sessionElapsedMs)

            listApps.removeAllViews()
            val inflater = LayoutInflater.from(requireContext())
            data.appUsages.take(15).forEach { app ->
                val row = inflater.inflate(R.layout.item_app_usage, listApps, false)
                row.findViewById<TextView>(R.id.tvAppName).text = app.label
                row.findViewById<TextView>(R.id.tvAppTime).text = fmt(app.timeMs)
                val bar    = row.findViewById<View>(R.id.viewUsageBar)
                val maxMs  = data.appUsages.firstOrNull()?.timeMs ?: 1L
                bar.post {
                    val pw = (bar.parent as? View)?.width ?: 0
                    if (pw > 0) {
                        bar.layoutParams = bar.layoutParams.also {
                            it.width = (pw * app.timeMs.toFloat() / maxMs).toInt().coerceAtLeast(4)
                        }
                        bar.requestLayout()
                    }
                }
                listApps.addView(row)
            }
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

    internal fun fmt(ms: Long): String {
        if (ms <= 0) return "0dk"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0 -> "${h}s ${m}dk"
            m > 0 -> "${m}dk ${s}sn"
            else  -> "${s}sn"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    companion object {
        fun resetSession(ctx: Context) {
            ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE).edit()
                .putLong("session_start_wall",    System.currentTimeMillis())
                .putLong("session_start_elapsed", SystemClock.elapsedRealtime())
                .putLong("session_start_uptime",  SystemClock.uptimeMillis())
                .apply()
        }

        /** Tüm ekran süresi verisini toplar. MainActivity ve Service de kullanır. */
        fun collectData(ctx: Context): ScreenData {
            val now = System.currentTimeMillis()
            val p   = ctx.getSharedPreferences("cpu_prefs", Context.MODE_PRIVATE)

            val sessionStartWall    = p.getLong("session_start_wall",    0L)
            val sessionStartElapsed = p.getLong("session_start_elapsed", 0L)
            val sessionStartUptime  = p.getLong("session_start_uptime",  0L)

            // Session başlangıcı: şarj takılınca veya manuel sıfırlamada kaydedilir
            val isChargePeriod = sessionStartWall > 0L && sessionStartWall < now
            // Session yoksa hiç veri gösterme
            if (!isChargePeriod) {
                return ScreenData(0L, 0L, 0L, 0L, 0L, emptyList(), false)
            }
            val periodStart = sessionStartWall

            // Session elapsed süresi
            val sessionElapsedMs = if (sessionStartElapsed > 0L)
                SystemClock.elapsedRealtime() - sessionStartElapsed
            else SystemClock.elapsedRealtime()

            // Deep sleep = elapsedRealtime delta - uptimeMillis delta (Battery Guru formülü)
            val deepSleepMs = if (sessionStartElapsed > 0L && sessionStartUptime > 0L) {
                val ed = SystemClock.elapsedRealtime() - sessionStartElapsed
                val ud = SystemClock.uptimeMillis()    - sessionStartUptime
                (ed - ud).coerceAtLeast(0L)
            } else {
                (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            }

            // UsageEvents ile ekran açık/kapalı hesapla
            val usm    = ctx.getSystemService(UsageStatsManager::class.java)
            val events = usm.queryEvents(periodStart, now)
            var screenOnMs  = 0L
            var screenOffMs = 0L
            var lastOnTime  = -1L
            var lastOffTime = -1L
            var firstScreenEvent = false
            val appFgStart  = mutableMapOf<String, Long>()
            val appFgTotal  = mutableMapOf<String, Long>()
            val event       = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        if (!firstScreenEvent) {
                            // İlk event SCREEN_ON ise: period başından beri kapalıydı
                            lastOffTime = periodStart
                            firstScreenEvent = true
                        }
                        if (lastOffTime >= 0L) {
                            screenOffMs += event.timeStamp - lastOffTime
                            lastOffTime = -1L
                        }
                        lastOnTime = event.timeStamp
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (!firstScreenEvent) {
                            // İlk event SCREEN_OFF ise: period başından beri açıktı
                            lastOnTime = periodStart
                            firstScreenEvent = true
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
            if (lastOnTime >= 0L) screenOnMs += now - lastOnTime
            // Hâlâ kapalıysa şimdiye kadar say
            if (lastOffTime >= 0L) screenOffMs += now - lastOffTime
            // Hiç screen event gelmediyse: şu an ekran açık kabul et
            if (!firstScreenEvent) screenOnMs = now - periodStart

            // Hâlâ foreground'da olan uygulamalar
            appFgStart.forEach { (pkg, start) ->
                appFgTotal[pkg] = (appFgTotal[pkg] ?: 0L) + (now - start)
            }

            // awake = session elapsed - deep sleep
            val awakeMs = (sessionElapsedMs - deepSleepMs).coerceAtLeast(0L)

            // Sistem uygulamalarını filtrele
            val pm = ctx.packageManager
            val appUsages = appFgTotal.filter { (pkg, ms) ->
                if (ms <= 0) return@filter false
                val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return@filter false }
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
            }.map { (pkg, ms) ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                            catch (_: Exception) { pkg }
                AppUsage(label, ms)
            }.sortedByDescending { it.timeMs }

            return ScreenData(sessionElapsedMs, deepSleepMs, awakeMs, screenOnMs, screenOffMs, appUsages, isChargePeriod)
        }
    }
}
