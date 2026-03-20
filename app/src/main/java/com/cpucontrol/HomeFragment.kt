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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Çekirdek durum göstergeleri
        val llCores = view.findViewById<LinearLayout>(R.id.llCoreStatus)
        val coreViews = (0..7).map { cpu ->
            val tv = TextView(requireContext()).apply {
                text = "cpu$cpu"
                textSize = 9f
                setPadding(8, 5, 8, 5)
                setTextColor(requireContext().getColor(R.color.text_primary))
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.marginEnd = if (cpu < 7) 3 else 0
            llCores.addView(tv, params)
            tv
        }

        // Statik sistem bilgileri (bir kez oku)
        view.findViewById<TextView>(R.id.tvKernelVer).text = System.getProperty("os.version") ?: "—"
        view.findViewById<TextView>(R.id.tvAndroidVer).text = "Android ${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})"

        // Aktif profil (prefs'ten)
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        view.findViewById<TextView>(R.id.tvActiveProfile).text =
            prefs.getString("active_profile", null)?.let { Profiles.isimler[it] } ?: "Manuel"
        view.findViewById<TextView>(R.id.tvAutoProfile).text =
            if (prefs.getBoolean("auto_profile", false)) "Otomatik" else "Manuel"

        // Canlı döngü başlat
        startLive(view, coreViews)
    }

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        val llCores = v.findViewById<LinearLayout>(R.id.llCoreStatus)
        val coreViews = (0 until llCores.childCount).map { llCores.getChildAt(it) as TextView }
        if (liveJob?.isActive != true) startLive(v, coreViews)
    }

    override fun onPause() {
        super.onPause()
        liveJob?.cancel()
    }

    private fun startLive(view: View, coreViews: List<TextView>) {
        liveJob?.cancel()
        val freqChart = view.findViewById<FreqChartView>(R.id.freqChart)

        liveJob = scope.launch {
            while (isActive) {
                // ── IO'da tüm verileri topla ──
                val d = withContext(Dispatchers.IO) { collectAll() }

                // ── CPU ──
                view.findViewById<TextView>(R.id.tvHomeTemp).apply {
                    text = if (d.tempC > 0) "${d.tempC}°C" else "—°C"
                    setTextColor(requireContext().getColor(when {
                        d.tempC >= 70 -> R.color.accent_orange
                        d.tempC >= 50 -> R.color.accent_yellow
                        else          -> R.color.accent_green
                    }))
                }
                view.findViewById<TextView>(R.id.tvLittleFreq).text =
                    if (d.littleMax > 0) "${d.littleMax / 1000} MHz" else "— MHz"
                view.findViewById<TextView>(R.id.tvBigFreq).text =
                    if (d.bigMax > 0) "${d.bigMax / 1000} MHz" else "— MHz"
                view.findViewById<TextView>(R.id.tvPrimeFreq).text =
                    if (d.primeMax > 0) "${d.primeMax / 1000} MHz" else "— MHz"
                view.findViewById<TextView>(R.id.tvLittleGov).text = d.littleGov
                view.findViewById<TextView>(R.id.tvBigGov).text    = d.bigGov
                view.findViewById<TextView>(R.id.tvPrimeGov).text  = d.primeGov

                // ── GPU ──
                view.findViewById<TextView>(R.id.tvGpuFreq).text =
                    if (d.gpuCur > 0) "${d.gpuCur / 1_000_000} MHz" else "— MHz"

                // ── RAM ──
                view.findViewById<TextView>(R.id.tvRamUsed).text =
                    if (d.ramUsedMb > 0) "${d.ramUsedMb} MB" else "—"
                view.findViewById<TextView>(R.id.tvRamTotal).text =
                    if (d.ramTotalMb > 0) "${d.ramTotalMb} MB toplam" else "—"

                // ── PİL ──
                val batIntent = requireContext().registerReceiver(null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batIntent?.let { bi ->
                    val level  = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale  = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct    = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == BatteryManager.BATTERY_STATUS_FULL
                    val tempBat = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    val voltage = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

                    view.findViewById<TextView>(R.id.tvBattery).apply {
                        text = if (pct >= 0) "$pct%" else "—%"
                        setTextColor(requireContext().getColor(when {
                            pct <= 15 -> R.color.accent_orange
                            pct <= 30 -> R.color.accent_yellow
                            else      -> R.color.accent_green
                        }))
                    }
                    view.findViewById<TextView>(R.id.tvCharging).apply {
                        text = if (isCharging) "⚡ Şarj oluyor" else "Şarj değil"
                        setTextColor(requireContext().getColor(
                            if (isCharging) R.color.accent_green else R.color.text_secondary))
                    }
                    view.findViewById<TextView>(R.id.tvBatTemp).text =
                        if (tempBat > 0) "${tempBat / 10.0}°C" else "—°C"
                    view.findViewById<TextView>(R.id.tvBatVoltage).text =
                        if (voltage > 0) "$voltage mV" else "— mV"
                }
                // Şarj akımı (BatteryManager API)
                val bm = requireContext().getSystemService(BatteryManager::class.java)
                val currentNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                view.findViewById<TextView>(R.id.tvBatCurrent).text =
                    if (currentNow != Long.MIN_VALUE && currentNow != 0L)
                        "${currentNow / 1000} mA"
                    else "— mA"

                // ── Çekirdekler ──
                val onlineCount = d.coreOnline.count { it }
                view.findViewById<TextView>(R.id.tvCoreOnlineCount).text = "$onlineCount/8"
                coreViews.forEachIndexed { i, tv ->
                    val online = d.coreOnline.getOrElse(i) { true }
                    val color = when {
                        !online -> R.color.text_hint
                        i <= 3  -> R.color.accent_green
                        i <= 6  -> R.color.accent_cyan
                        else    -> R.color.accent_purple
                    }
                    tv.setTextColor(requireContext().getColor(color))
                    tv.alpha = if (online) 1f else 0.4f
                }

                // ── Sistem ──
                view.findViewById<TextView>(R.id.tvSysUptime).text = fmtUptime(SystemClock.elapsedRealtime())
                view.findViewById<TextView>(R.id.tvTcpCongestion).text = d.tcpCongestion

                // ── Ekran süresi özeti ──
                val sd = d.screenData
                if (sd != null && sd.sessionElapsedMs > 0L) {
                    view.findViewById<TextView>(R.id.tvHomeScreenOn).text = fmtMs(sd.screenOnMs)
                    view.findViewById<TextView>(R.id.tvHomeDeepSleep).text = "💤 ${fmtMs(sd.deepSleepMs)}"
                    view.findViewById<TextView>(R.id.tvHomeScreenPeriod).text =
                        if (sd.isChargePeriod) "Şarjdan bu yana" else "Son 24 saat"
                } else {
                    view.findViewById<TextView>(R.id.tvHomeScreenOn).text = "—"
                    view.findViewById<TextView>(R.id.tvHomeDeepSleep).text = "💤 —"
                    view.findViewById<TextView>(R.id.tvHomeScreenPeriod).text = "Ölçüm yok"
                }

                // ── Grafik ──
                freqChart.addPoint(
                    (d.littleCur / 1000f).coerceAtLeast(0f),
                    (d.bigCur    / 1000f).coerceAtLeast(0f),
                    (d.primeCur  / 1000f).coerceAtLeast(0f)
                )

                delay(2_000)
            }
        }
    }

    private data class AllData(
        val tempC: Int,
        val littleMax: Int, val bigMax: Int, val primeMax: Int,
        val littleCur: Int, val bigCur: Int,  val primeCur: Int,
        val littleGov: String, val bigGov: String, val primeGov: String,
        val gpuCur: Long,
        val ramUsedMb: Long, val ramTotalMb: Long,
        val coreOnline: List<Boolean>,
        val tcpCongestion: String,
        val screenData: ScreenTimeFragment.ScreenData?
    )

    private fun collectAll(): AllData {
        val tempC     = RootHelper.getCpuTemp()
        val littleMax = RootHelper.getCpuMaxFreq(0)
        val bigMax    = RootHelper.getCpuMaxFreq(4)
        val primeMax  = RootHelper.getCpuMaxFreq(7)
        val littleCur = RootHelper.getCpuCurFreq(0)
        val bigCur    = RootHelper.getCpuCurFreq(4)
        val primeCur  = RootHelper.getCpuCurFreq(7)
        val littleGov = RootHelper.getGovernor(0)
        val bigGov    = RootHelper.getGovernor(4)
        val primeGov  = RootHelper.getGovernor(7)
        val gpuCur    = RootHelper.getGpuCurFreq()
        val coreOnline = (0..7).map { RootHelper.isCpuOnline(it) }
        val tcpCong   = RootHelper.getTcpCongestion()

        // RAM
        val am = requireContext().getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val ramTotalMb = mi.totalMem / 1024 / 1024
        val ramUsedMb  = (mi.totalMem - mi.availMem) / 1024 / 1024

        // Ekran süresi (izin varsa)
        val screenData = try {
            val appOps = requireContext().getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), requireContext().packageName
            )
            if (mode == android.app.AppOpsManager.MODE_ALLOWED)
                ScreenTimeFragment.collectData(requireContext())
            else null
        } catch (_: Exception) { null }

        return AllData(
            tempC, littleMax, bigMax, primeMax,
            littleCur, bigCur, primeCur,
            littleGov, bigGov, primeGov,
            gpuCur, ramUsedMb, ramTotalMb,
            coreOnline, tcpCong, screenData
        )
    }

    private fun fmtUptime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return "${h}s ${m}dk"
    }

    private fun fmtMs(ms: Long): String {
        if (ms <= 0) return "0dk"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}s ${m}dk" else "${m}dk"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveJob?.cancel()
        scope.cancel()
    }
}
