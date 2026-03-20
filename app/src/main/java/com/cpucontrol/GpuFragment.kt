package com.cpucontrol

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class GpuFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var freqs = RootHelper.GPU_FREQS  // başlangıçta sabit liste, sonra cihazdan güncellenir
    private var autoRefreshJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gpu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvCurMin      = view.findViewById<TextView>(R.id.tvGpuCurrentMin)
        val tvCurMax      = view.findViewById<TextView>(R.id.tvGpuCurrentMax)
        val tvCurMaxSmall = view.findViewById<TextView>(R.id.tvGpuCurrentMaxSmall)
        val tvMinSel      = view.findViewById<TextView>(R.id.tvGpuMinSelected)
        val tvMaxSel      = view.findViewById<TextView>(R.id.tvGpuMaxSelected)
        val seekMin       = view.findViewById<SeekBar>(R.id.seekGpuMin)
        val seekMax       = view.findViewById<SeekBar>(R.id.seekGpuMax)
        val btnApply      = view.findViewById<MaterialButton>(R.id.btnGpuApply)
        val tvStatus      = view.findViewById<TextView>(R.id.tvGpuStatus)

        seekMin.max = freqs.size - 1
        seekMax.max = freqs.size - 1

        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        val savedMin = prefs.getInt("gpu_min", freqs.first())
        val savedMax = prefs.getInt("gpu_max", freqs.last())
        val minIdx = freqs.indexOf(savedMin).coerceAtLeast(0)
        val maxIdx = freqs.indexOf(savedMax).let { if (it < 0) freqs.size - 1 else it }
        seekMin.progress = minIdx
        seekMax.progress = maxIdx
        tvMinSel.text = "${freqs[minIdx] / 1000000} MHz"
        tvMaxSel.text = "${freqs[maxIdx] / 1000000} MHz"

        // Cihazdan gerçek GPU frekanslarını yükle
        scope.launch {
            val deviceFreqs = withContext(Dispatchers.IO) { RootHelper.readGpuFreqsFromDevice() }
            if (deviceFreqs != freqs) {
                freqs = deviceFreqs
                seekMin.max = freqs.size - 1
                seekMax.max = freqs.size - 1
                val newMinIdx = freqs.indexOfFirst { it >= savedMin }.coerceAtLeast(0)
                val newMaxIdx = freqs.indexOfLast { it <= savedMax }.let { if (it < 0) freqs.size - 1 else it }
                seekMin.progress = newMinIdx
                seekMax.progress = newMaxIdx
                tvMinSel.text = "${freqs[newMinIdx] / 1000000} MHz"
                tvMaxSel.text = "${freqs[newMaxIdx] / 1000000} MHz"
            }
        }(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvMinSel.text = "${freqs[p] / 1000000} MHz"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (sb.progress > seekMax.progress) sb.progress = seekMax.progress
            }
        })

        seekMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvMaxSel.text = "${freqs[p] / 1000000} MHz"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (sb.progress < seekMin.progress) sb.progress = seekMin.progress
            }
        })

        btnApply.setOnClickListener {
            val minFreq = freqs[seekMin.progress]
            val maxFreq = freqs[seekMax.progress]
            btnApply.isEnabled = false
            tvStatus.text = "Uygulanıyor..."
            scope.launch {
                val gpuPath = withContext(Dispatchers.IO) { RootHelper.getGpuPathCached() }
                val ok = withContext(Dispatchers.IO) {
                    RootHelper.setGpuMinFreq(minFreq) && RootHelper.setGpuMaxFreq(maxFreq)
                }
                btnApply.isEnabled = true
                if (ok) {
                    prefs.edit().putInt("gpu_min", minFreq).putInt("gpu_max", maxFreq).apply()
                    tvStatus.text = "Kaydedildi  ·  ${minFreq / 1000000} – ${maxFreq / 1000000} MHz"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                } else {
                    tvStatus.text = "Başarısız — path: $gpuPath"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
                refreshCurrent(tvCurMin, tvCurMax, tvCurMaxSmall)
            }
        }

        refreshCurrent(tvCurMin, tvCurMax, tvCurMaxSmall)

        // ── Yenileme hızı ──────────────────────────────────────────────────
        val tvCurrentRefresh = view.findViewById<TextView>(R.id.tvCurrentRefresh)
        val tvRefreshStatus  = view.findViewById<TextView>(R.id.tvRefreshStatus)
        val tvAutoInfo       = view.findViewById<TextView>(R.id.tvAutoRefreshInfo)
        val btn60            = view.findViewById<MaterialButton>(R.id.btn60hz)
        val btn90            = view.findViewById<MaterialButton>(R.id.btn90hz)
        val btn120           = view.findViewById<MaterialButton>(R.id.btn120hz)
        val btnAuto          = view.findViewById<MaterialButton>(R.id.btnAutoHz)

        // Kayıtlı durumu yükle
        val autoEnabled = prefs.getBoolean("auto_refresh_enabled", false)
        setAutoMode(autoEnabled, btnAuto, btn60, btn90, btn120, tvAutoInfo, tvRefreshStatus, tvCurrentRefresh, prefs)

        scope.launch {
            val rate = withContext(Dispatchers.IO) { getCurrentRefreshRate() }
            tvCurrentRefresh.text = if (rate > 0) "$rate Hz" else "— Hz"
            if (!prefs.getBoolean("auto_refresh_enabled", false))
                highlightRefreshBtn(rate, btn60, btn90, btn120, btnAuto)
        }

        fun applyRefresh(hz: Int) {
            // Manuel seçim — otomatik modu kapat
            prefs.edit().putBoolean("auto_refresh_enabled", false).apply()
            autoRefreshJob?.cancel()
            setAutoMode(false, btnAuto, btn60, btn90, btn120, tvAutoInfo, tvRefreshStatus, tvCurrentRefresh, prefs)

            scope.launch {
                tvRefreshStatus.text = "Uygulanıyor..."
                val ok = withContext(Dispatchers.IO) { RootHelper.setRefreshRate(hz) }
                if (ok) {
                    tvCurrentRefresh.text = "$hz Hz"
                    tvRefreshStatus.text = "✓ $hz Hz uygulandı"
                    tvRefreshStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                    highlightRefreshBtn(hz, btn60, btn90, btn120, btnAuto)
                    prefs.edit().putInt("refresh_rate", hz).apply()
                } else {
                    tvRefreshStatus.text = "✗ Başarısız — root gerekli"
                    tvRefreshStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        btn60.setOnClickListener  { applyRefresh(60) }
        btn90.setOnClickListener  { applyRefresh(90) }
        btn120.setOnClickListener { applyRefresh(120) }

        btnAuto.setOnClickListener {
            val nowAuto = !prefs.getBoolean("auto_refresh_enabled", false)
            prefs.edit().putBoolean("auto_refresh_enabled", nowAuto).apply()
            setAutoMode(nowAuto, btnAuto, btn60, btn90, btn120, tvAutoInfo, tvRefreshStatus, tvCurrentRefresh, prefs)
        }
    }

    /**
     * Otomatik mod açık/kapalı durumunu ayarlar.
     * Açıksa 30 saniyede bir senaryoyu değerlendirir.
     */
    private fun setAutoMode(
        enabled: Boolean,
        btnAuto: MaterialButton,
        btn60: MaterialButton, btn90: MaterialButton, btn120: MaterialButton,
        tvInfo: TextView, tvStatus: TextView, tvCurrent: TextView,
        prefs: android.content.SharedPreferences
    ) {
        val ctx = requireContext()
        if (enabled) {
            btnAuto.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(R.color.accent_cyan))
            btnAuto.setTextColor(ctx.getColor(R.color.on_primary))
            btn60.alpha = 0.4f; btn90.alpha = 0.4f; btn120.alpha = 0.4f
            btn60.isEnabled = false; btn90.isEnabled = false; btn120.isEnabled = false

            autoRefreshJob?.cancel()
            autoRefreshJob = scope.launch {
                while (isActive) {
                    val (hz, reason) = withContext(Dispatchers.IO) { decideRefreshRate() }
                    val ok = withContext(Dispatchers.IO) { RootHelper.setRefreshRate(hz) }
                    if (ok) {
                        tvCurrent.text = "$hz Hz"
                        tvInfo.text = "Otomatik: $reason"
                        tvInfo.setTextColor(ctx.getColor(R.color.accent_cyan))
                        tvStatus.text = ""
                        highlightRefreshBtn(hz, btn60, btn90, btn120, null)
                    }
                    delay(30_000) // 30 saniyede bir kontrol
                }
            }
        } else {
            autoRefreshJob?.cancel()
            btnAuto.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ctx.getColor(R.color.bg_elevated))
            btnAuto.setTextColor(ctx.getColor(R.color.text_secondary))
            btn60.alpha = 1f; btn90.alpha = 1f; btn120.alpha = 1f
            btn60.isEnabled = true; btn90.isEnabled = true; btn120.isEnabled = true
            tvInfo.text = ""
        }
    }

    /**
     * Senaryoya göre yenileme hızı kararı verir.
     * Öncelik sırası: Oyun > Düşük pil > Şarj > Normal
     */
    private fun decideRefreshRate(): Pair<Int, String> {
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        // Oyun algılama aktif mi ve oyun çalışıyor mu?
        val gameDetectEnabled = prefs.getBoolean("game_detect_enabled", false)
        if (gameDetectEnabled) {
            val activeProfile = prefs.getString("active_profile", "") ?: ""
            if (activeProfile == "oyun") {
                return Pair(120, "Oyun modu aktif → 120 Hz")
            }
        }

        // Pil durumu
        val batteryIntent = requireContext().registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

        return when {
            pct <= 15 -> Pair(60, "Pil kritik (%$pct) → 60 Hz")
            pct <= 30 -> Pair(60, "Pil düşük (%$pct) → 60 Hz")
            isCharging -> Pair(120, "Şarj oluyor → 120 Hz")
            pct >= 80  -> Pair(120, "Pil dolu (%$pct) → 120 Hz")
            else       -> Pair(90, "Normal kullanım → 90 Hz")
        }
    }

    private fun getCurrentRefreshRate(): Int {
        val (_, out) = RootHelper.runAsRoot(
            "dumpsys display | grep 'mRefreshRate\\|refreshRate\\|fps' | head -3"
        )
        // Sayısal değer bul (60.0, 90.0, 120.0 gibi)
        val match = Regex("(\\d{2,3})\\.?\\d*\\s*(?:Hz|fps|mRefreshRate)", RegexOption.IGNORE_CASE)
            .find(out)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun highlightRefreshBtn(
        hz: Int,
        btn60: MaterialButton, btn90: MaterialButton, btn120: MaterialButton,
        btnAuto: MaterialButton?
    ) {
        val ctx = requireContext()
        val activeColor  = ctx.getColor(R.color.accent_cyan)
        val inactiveColor = ctx.getColor(R.color.bg_elevated)
        val activeText   = ctx.getColor(R.color.on_primary)
        val inactiveText = ctx.getColor(R.color.text_secondary)

        btn60.backgroundTintList  = android.content.res.ColorStateList.valueOf(if (hz == 60) activeColor else inactiveColor)
        btn90.backgroundTintList  = android.content.res.ColorStateList.valueOf(if (hz == 90) activeColor else inactiveColor)
        btn120.backgroundTintList = android.content.res.ColorStateList.valueOf(if (hz == 120) activeColor else inactiveColor)
        btn60.setTextColor(if (hz == 60) activeText else inactiveText)
        btn90.setTextColor(if (hz == 90) activeText else inactiveText)
        btn120.setTextColor(if (hz == 120) activeText else inactiveText)
    }

    private fun refreshCurrent(tvMin: TextView, tvMax: TextView, tvMaxSmall: TextView) {
        scope.launch {
            val (min, max) = withContext(Dispatchers.IO) {
                Pair(RootHelper.getGpuMinFreq(), RootHelper.getGpuMaxFreq())
            }
            tvMin.text = if (min > 0) "${min / 1000000} MHz" else "N/A"
            tvMax.text = if (max > 0) "${max / 1000000} MHz" else "N/A"
            tvMaxSmall.text = if (max > 0) "${max / 1000000} MHz" else "N/A"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoRefreshJob?.cancel()
        scope.cancel()
    }
}
