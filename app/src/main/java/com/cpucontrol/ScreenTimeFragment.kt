package com.cpucontrol

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
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
        val btnGrant      = view.findViewById<MaterialButton>(R.id.btnGrantUsage)
        val layoutPerm    = view.findViewById<View>(R.id.layoutPermission)
        val layoutContent = view.findViewById<View>(R.id.layoutContent)

        btnGrant.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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
        val view = view ?: return
        if (hasUsagePermission()) {
            view.findViewById<View>(R.id.layoutPermission).visibility = View.GONE
            view.findViewById<View>(R.id.layoutContent).visibility    = View.VISIBLE
            loadData(view)
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
        val tvScreenOn   = view.findViewById<TextView>(R.id.tvScreenOn)
        val tvScreenOff  = view.findViewById<TextView>(R.id.tvScreenOff)
        val tvDeepSleep  = view.findViewById<TextView>(R.id.tvDeepSleep)
        val tvUptime     = view.findViewById<TextView>(R.id.tvUptime)
        val listApps     = view.findViewById<LinearLayout>(R.id.listAppUsage)

        scope.launch {
            val data = withContext(Dispatchers.IO) { collectData() }

            tvScreenOn.text  = "Ekran Açık: ${formatDuration(data.screenOnMs)}"
            tvScreenOff.text = "Ekran Kapalı: ${formatDuration(data.screenOffMs)}"
            tvDeepSleep.text = "Deep Sleep: ${formatDuration(data.deepSleepMs)}"
            tvUptime.text    = "Çalışma Süresi: ${formatDuration(data.uptimeMs)}"

            listApps.removeAllViews()
            val inflater = LayoutInflater.from(requireContext())
            data.appUsages.take(15).forEach { app ->
                val row = inflater.inflate(R.layout.item_app_usage, listApps, false)
                row.findViewById<TextView>(R.id.tvAppName).text  = app.label
                row.findViewById<TextView>(R.id.tvAppTime).text  = formatDuration(app.timeMs)
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

    private fun collectData(): ScreenData {
        val ctx = requireContext()
        val pm  = ctx.getSystemService(PowerManager::class.java)

        // Uptime ve deep sleep
        val uptimeMs    = android.os.SystemClock.elapsedRealtime()
        val deepSleepMs = uptimeMs - android.os.SystemClock.uptimeMillis()

        // Ekran açık/kapalı süresi — son 24 saat UsageStats
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val start24h = now - TimeUnit.HOURS.toMillis(24)

        val events = usm.queryEvents(start24h, now)
        var screenOnMs  = 0L
        var lastOnTime  = 0L
        val event = android.app.usage.UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE -> lastOnTime = event.timeStamp
                android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (lastOnTime > 0) {
                        screenOnMs += event.timeStamp - lastOnTime
                        lastOnTime = 0
                    }
                }
            }
        }
        // Eğer şu an ekran açıksa
        if (lastOnTime > 0) screenOnMs += now - lastOnTime

        val screenOffMs = TimeUnit.HOURS.toMillis(24) - screenOnMs

        // Uygulama kullanım süreleri — son 24 saat
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start24h, now)
        val pm2 = ctx.packageManager
        val appUsages = stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .map { stat ->
                val label = try {
                    pm2.getApplicationLabel(pm2.getApplicationInfo(stat.packageName, 0)).toString()
                } catch (_: Exception) { stat.packageName }
                AppUsage(label, stat.totalTimeInForeground)
            }

        return ScreenData(
            uptimeMs    = uptimeMs,
            deepSleepMs = deepSleepMs,
            screenOnMs  = screenOnMs,
            screenOffMs = screenOffMs.coerceAtLeast(0),
            appUsages   = appUsages
        )
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0 dk"
        val h  = TimeUnit.MILLISECONDS.toHours(ms)
        val m  = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s  = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0  -> "${h}s ${m}dk"
            m > 0  -> "${m}dk ${s}sn"
            else   -> "${s}sn"
        }
    }

    data class AppUsage(val label: String, val timeMs: Long)
    data class ScreenData(
        val uptimeMs: Long,
        val deepSleepMs: Long,
        val screenOnMs: Long,
        val screenOffMs: Long,
        val appUsages: List<AppUsage>
    )

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
