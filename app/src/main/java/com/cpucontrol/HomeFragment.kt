package com.cpucontrol

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class HomeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvTemp     = view.findViewById<TextView>(R.id.tvHomeTemp)
        val tvLittle   = view.findViewById<TextView>(R.id.tvLittleFreq)
        val tvBig      = view.findViewById<TextView>(R.id.tvBigFreq)
        val tvPrime    = view.findViewById<TextView>(R.id.tvPrimeFreq)
        val tvBattery  = view.findViewById<TextView>(R.id.tvBattery)
        val tvCharging = view.findViewById<TextView>(R.id.tvCharging)
        val tvGpu      = view.findViewById<TextView>(R.id.tvGpuFreq)
        val tvProfile  = view.findViewById<TextView>(R.id.tvActiveProfile)
        val tvAuto     = view.findViewById<TextView>(R.id.tvAutoProfile)
        val llCores    = view.findViewById<LinearLayout>(R.id.llCoreStatus)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btnRefresh)
        val freqChart  = view.findViewById<FreqChartView>(R.id.freqChart)

        // Çekirdek durum göstergeleri
        val coreViews = (0..7).map { cpu ->
            val tv = TextView(requireContext()).apply {
                text = "cpu$cpu"
                textSize = 10f
                setPadding(10, 6, 10, 6)
                setTextColor(requireContext().getColor(R.color.text_primary))
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.marginEnd = if (cpu < 7) 4 else 0
            llCores.addView(tv, params)
            tv
        }

        // Pil bilgisi (statik, broadcast)
        val batteryIntent = requireContext().registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            tvBattery.text = if (pct >= 0) "$pct%" else "—%"
            tvCharging.text = if (isCharging) "⚡ Şarj oluyor" else "Şarj değil"
            tvCharging.setTextColor(requireContext().getColor(
                if (isCharging) R.color.accent_green else R.color.text_secondary))
        }

        // Aktif profil
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        tvProfile.text = prefs.getString("active_profile", null)
            ?.let { Profiles.isimler[it] } ?: "Manuel"
        tvAuto.text = if (prefs.getBoolean("auto_profile", false)) "Otomatik" else "Manuel"

        btnRefresh.setOnClickListener {
            loadOnce(tvTemp, tvLittle, tvBig, tvPrime, tvGpu, coreViews)
        }

        // İlk yükleme
        loadOnce(tvTemp, tvLittle, tvBig, tvPrime, tvGpu, coreViews)

        // Canlı grafik döngüsü — her 1 saniyede bir
        startLiveChart(freqChart)
    }

    private fun startLiveChart(chart: FreqChartView) {
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive) {
                val (little, big, prime) = withContext(Dispatchers.IO) {
                    Triple(
                        (RootHelper.getCpuCurFreq(0) / 1000f).coerceAtLeast(0f),
                        (RootHelper.getCpuCurFreq(4) / 1000f).coerceAtLeast(0f),
                        (RootHelper.getCpuCurFreq(7) / 1000f).coerceAtLeast(0f)
                    )
                }
                chart.addPoint(little, big, prime)
                delay(1_000)
            }
        }
    }

    private fun loadOnce(
        tvTemp: TextView, tvLittle: TextView, tvBig: TextView,
        tvPrime: TextView, tvGpu: TextView, coreViews: List<TextView>
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val tempRaw = RootHelper.runAsRoot("cat /sys/class/thermal/thermal_zone0/temp")
                    .second.trim().toIntOrNull() ?: -1
                val tempC = if (tempRaw > 1000) tempRaw / 1000 else tempRaw
                val littleMax = RootHelper.getCpuMaxFreq(0)
                val bigMax    = RootHelper.getCpuMaxFreq(4)
                val primeMax  = RootHelper.getCpuMaxFreq(7)
                val gpuCur    = RootHelper.runAsRoot("cat ${RootHelper.GPU_PATH}/cur_freq")
                    .second.trim().toLongOrNull() ?: -1L
                val onlineStates = (0..7).map { RootHelper.isCpuOnline(it) }
                listOf(tempC, littleMax, bigMax, primeMax, gpuCur.toInt()) to onlineStates
            }

            val (nums, onlineStates) = result
            val tempC     = nums[0]
            val littleMax = nums[1]
            val bigMax    = nums[2]
            val primeMax  = nums[3]
            val gpuCur    = nums[4]

            tvTemp.text   = if (tempC > 0) "$tempC°C" else "—°C"
            tvLittle.text = if (littleMax > 0) "${littleMax / 1000} MHz" else "— MHz"
            tvBig.text    = if (bigMax > 0) "${bigMax / 1000} MHz" else "— MHz"
            tvPrime.text  = if (primeMax > 0) "${primeMax / 1000} MHz" else "— MHz"
            tvGpu.text    = if (gpuCur > 0) "${gpuCur / 1000000} MHz" else "— MHz"

            tvTemp.setTextColor(requireContext().getColor(when {
                tempC >= 70 -> R.color.accent_orange
                tempC >= 50 -> R.color.accent_yellow
                else        -> R.color.accent_green
            }))

            coreViews.forEachIndexed { i, tv ->
                val online = onlineStates[i]
                val color = when {
                    !online -> R.color.text_hint
                    i <= 3  -> R.color.accent_green
                    i <= 6  -> R.color.accent_cyan
                    else    -> R.color.accent_purple
                }
                tv.setTextColor(requireContext().getColor(color))
                tv.alpha = if (online) 1f else 0.5f
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveJob?.cancel()
        scope.cancel()
    }
}
