package com.cpucontrol

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
    private val freqs = RootHelper.GPU_FREQS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_gpu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvCurMin      = view.findViewById<TextView>(R.id.tvGpuCurrentMin)
        val tvCurMax      = view.findViewById<TextView>(R.id.tvGpuCurrentMax)       // hero badge
        val tvCurMaxSmall = view.findViewById<TextView>(R.id.tvGpuCurrentMaxSmall)  // küçük kutu
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

        seekMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
                val ok = withContext(Dispatchers.IO) {
                    RootHelper.setGpuMinFreq(minFreq) && RootHelper.setGpuMaxFreq(maxFreq)
                }
                btnApply.isEnabled = true
                if (ok) {
                    prefs.edit().putInt("gpu_min", minFreq).putInt("gpu_max", maxFreq).apply()
                    tvStatus.text = "Kaydedildi  ·  ${minFreq / 1000000} – ${maxFreq / 1000000} MHz"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                } else {
                    tvStatus.text = "Uygulama başarısız"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
                refreshCurrent(tvCurMin, tvCurMax, tvCurMaxSmall)
            }
        }

        refreshCurrent(tvCurMin, tvCurMax, tvCurMaxSmall)
    }

    private fun refreshCurrent(tvMin: TextView, tvMax: TextView, tvMaxSmall: TextView) {
        scope.launch {
            val (min, max) = withContext(Dispatchers.IO) {
                Pair(RootHelper.getGpuMinFreq(), RootHelper.getGpuMaxFreq())
            }
            val minStr = if (min > 0) "${min / 1000000} MHz" else "N/A"
            val maxStr = if (max > 0) "${max / 1000000} MHz" else "N/A"
            tvMin.text = minStr
            tvMax.text = maxStr
            tvMaxSmall.text = maxStr
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
