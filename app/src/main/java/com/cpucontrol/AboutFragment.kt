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
import java.util.concurrent.TimeUnit

class AboutFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_about, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Statik — bir kez oku
        view.findViewById<TextView>(R.id.tvSysDevice).text =
            "${Build.MANUFACTURER} ${Build.MODEL}  (${Build.DEVICE})"
        view.findViewById<TextView>(R.id.tvSysAndroid).text =
            "Android ${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})"
        view.findViewById<TextView>(R.id.tvSysKernel).text =
            System.getProperty("os.version") ?: "—"
        view.findViewById<TextView>(R.id.tvSysBuild).text =
            Build.DISPLAY ?: "—"
        view.findViewById<TextView>(R.id.tvSysArch).text =
            Build.SUPPORTED_ABIS.firstOrNull() ?: "—"
        view.findViewById<TextView>(R.id.tvSysSecPatch).text =
            Build.VERSION.SECURITY_PATCH ?: "—"

        val am = requireContext().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        view.findViewById<TextView>(R.id.tvSysRamTotal).text =
            "%.1f GB".format(mi.totalMem / 1024.0 / 1024.0 / 1024.0)

        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        view.findViewById<TextView>(R.id.tvSysStorage).text =
            "%.1f GB toplam  ·  %.1f GB boş".format(
                stat.totalBytes / 1024.0 / 1024.0 / 1024.0,
                stat.availableBytes / 1024.0 / 1024.0 / 1024.0
            )

        // Pil (broadcast)
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
                if (pct >= 0) "$pct%  ${if (isCharging) "⚡" else ""}" else "—"
            view.findViewById<TextView>(R.id.tvSysBatTemp).text =
                if (temp > 0) "${temp / 10.0}°C" else "—"
            view.findViewById<TextView>(R.id.tvSysBatVolt).text =
                if (volt > 0) "$volt mV" else "—"
            view.findViewById<TextView>(R.id.tvSysBatHealth).text = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD         -> "İyi"
                BatteryManager.BATTERY_HEALTH_OVERHEAT     -> "Aşırı ısınma"
                BatteryManager.BATTERY_HEALTH_DEAD         -> "Ölü"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Aşırı voltaj"
                BatteryManager.BATTERY_HEALTH_COLD         -> "Soğuk"
                else -> "—"
            }
        }

        // Root + CPU/GPU — IO'da çalıştır
        scope.launch {
            val hasRoot = withContext(Dispatchers.IO) { RootHelper.checkRoot() }
            view.findViewById<TextView>(R.id.tvSysRootStatus).apply {
                text = if (hasRoot) "✓ Aktif" else "✗ Yok"
                setTextColor(requireContext().getColor(
                    if (hasRoot) R.color.accent_green else R.color.accent_orange))
            }

            val tempC   = withContext(Dispatchers.IO) { RootHelper.getCpuTemp() }
            val gpuHz   = withContext(Dispatchers.IO) { RootHelper.getGpuCurFreq() }
            val tcpCong = withContext(Dispatchers.IO) { RootHelper.getTcpCongestion() }
            val gov     = withContext(Dispatchers.IO) { RootHelper.getGovernor(0) }

            view.findViewById<TextView>(R.id.tvSysCpuTemp).text =
                if (tempC > 0) "$tempC°C" else "—"
            view.findViewById<TextView>(R.id.tvSysGpuFreq).text =
                if (gpuHz > 0) "${gpuHz / 1_000_000} MHz" else "—"
            view.findViewById<TextView>(R.id.tvSysTcp).text = tcpCong
            view.findViewById<TextView>(R.id.tvSysGovernor).text = gov
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
