package com.cpucontrol

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

class AboutFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Statik bilgiler (bir kez)
        view.findViewById<TextView>(R.id.tvSysAndroid).text =
            "Android ${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})"
        view.findViewById<TextView>(R.id.tvSysKernel).text =
            System.getProperty("os.version") ?: "—"
        view.findViewById<TextView>(R.id.tvSysDevice).text =
            "${Build.MANUFACTURER} ${Build.MODEL}  (${Build.DEVICE})"
        view.findViewById<TextView>(R.id.tvSysBuild).text =
            Build.DISPLAY ?: "—"
        view.findViewById<TextView>(R.id.tvSysArch).text =
            Build.SUPPORTED_ABIS.firstOrNull() ?: "—"
        view.findViewById<TextView>(R.id.tvSysSecPatch).text =
            Build.VERSION.SECURITY_PATCH ?: "—"

        // RAM toplam
        val am = requireContext().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / 1024.0 / 1024.0 / 1024.0
        view.findViewById<TextView>(R.id.tvSysRamTotal).text = "%.1f GB".format(totalGb)

        // Depolama
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes / 1024.0 / 1024.0 / 1024.0
        val freeStorage  = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        view.findViewById<TextView>(R.id.tvSysStorage).text =
            "%.1f GB toplam  ·  %.1f GB boş".format(totalStorage, freeStorage)

        // Dinamik bilgiler
        loadDynamic(view)
    }

    private fun loadDynamic(view: View) {
        scope.launch {
            val d = withContext(Dispatchers.IO) { collectDynamic() }

            view.findViewById<TextView>(R.id.tvSysUptime).text = d.uptime
            view.findViewById<TextView>(R.id.tvSysRamUsed).text = d.ramUsed
            view.findViewById<TextView>(R.id.tvSysRootStatus).apply {
                text = if (d.hasRoot) "✓ Root erişimi aktif" else "✗ Root erişimi yok"
                setTextColor(requireContext().getColor(
                    if (d.hasRoot) R.color.accent_green else R.color.accent_orange))
            }
            view.findViewById<TextView>(R.id.tvSysCpuTemp).text = d.cpuTemp
            view.findViewById<TextView>(R.id.tvSysGpuFreq).text = d.gpuFreq
            view.findViewById<TextView>(R.id.tvSysTcp).text = d.tcpCong
            view.findViewById<TextView>(R.id.tvSysGovernor).text = d.governor

            // Pil
            val bi = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            bi?.let {
                val level  = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale  = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct    = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val temp   = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                val volt   = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                view.findViewById<TextView>(R.id.tvSysBatLevel).text =
                    if (pct >= 0) "$pct%  ${if (isCharging) "⚡ Şarj oluyor" else ""}" else "—"
                view.findViewById<TextView>(R.id.tvSysBatTemp).text =
                    if (temp > 0) "${temp / 10.0}°C" else "—"
                view.findViewById<TextView>(R.id.tvSysBatVolt).text =
                    if (volt > 0) "$volt mV" else "—"
                view.findViewById<TextView>(R.id.tvSysBatHealth).text = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD          -> "İyi"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT      -> "Aşırı ısınma"
                    BatteryManager.BATTERY_HEALTH_DEAD          -> "Ölü"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE  -> "Aşırı voltaj"
                    BatteryManager.BATTERY_HEALTH_COLD          -> "Soğuk"
                    else -> "—"
                }
            }
        }
    }

    private data class DynData(
        val uptime: String,
        val ramUsed: String,
        val hasRoot: Boolean,
        val cpuTemp: String,
        val gpuFreq: String,
        val tcpCong: String,
        val governor: String
    )

    private fun collectDynamic(): DynData {
        val uptimeMs = SystemClock.elapsedRealtime()
        val h = TimeUnit.MILLISECONDS.toHours(uptimeMs)
        val m = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60
        val uptime = "${h}s ${m}dk"

        val am = requireContext().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val usedMb = (mi.totalMem - mi.availMem) / 1024 / 1024
        val totalMb = mi.totalMem / 1024 / 1024
        val ramUsed = "$usedMb MB / $totalMb MB"

        val hasRoot = RootHelper.checkRoot()
        val tempC   = RootHelper.getCpuTemp()
        val cpuTemp = if (tempC > 0) "$tempC°C" else "—"
        val gpuHz   = RootHelper.getGpuCurFreq()
        val gpuFreq = if (gpuHz > 0) "${gpuHz / 1_000_000} MHz" else "—"
        val tcpCong = RootHelper.getTcpCongestion()
        val gov     = RootHelper.getGovernor(0)

        return DynData(uptime, ramUsed, hasRoot, cpuTemp, gpuFreq, tcpCong, gov)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
