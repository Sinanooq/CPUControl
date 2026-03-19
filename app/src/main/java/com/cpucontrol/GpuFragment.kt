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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_gpu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvCurMin  = view.findViewById<TextView>(R.id.tvGpuCurrentMin)
        val tvCurMax  = view.findViewById<TextView>(R.id.tvGpuCurrentMax)
        val tvMinSel  = view.findViewById<TextView>(R.id.tvGpuMinSelected)
        val tvMaxSel  = view.findViewById<TextView>(R.id.tvGpuMaxSelected)
        val seekMin   = view.findViewById<SeekBar>(R.id.seekGpuMin)
        val seekMax   = view.findViewById<SeekBar>(R.id.seekGpuMax)
        val btnApply  = view.findViewById<MaterialButton>(R.id.btnGpuApply)
        val tvStatus  = view.findViewById<TextView>(R.id.tvGpuStatus)

        seekMin.max = freqs.size - 1
        seekMax.max = freqs.size - 1

        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        val savedMin = prefs.getInt("gpu_min", freqs.first())
        val savedMax = prefs.getInt("gpu_max", freqs.last())
        val minIdx = freqs.indexOf(savedMin).coerceAtLeast(0)
        val maxIdx = freqs.indexOf(savedMax).coerceAtLeast(freqs.size - 1)
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
            tvStatus.text = "Applying..."

            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val r1 = RootHelper.setGpuMinFreq(minFreq)
                    val r2 = RootHelper.setGpuMaxFreq(maxFreq)
                    r1 && r2
                }
                btnApply.isEnabled = true
                if (ok) {
                    prefs.edit().putInt("gpu_min", minFreq).putInt("gpu_max", maxFreq).apply()
                    tvStatus.text = "Saved  •  ${minFreq / 1000000} – ${maxFreq / 1000000} MHz"
                    tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                } else {
                    tvStatus.text = "Failed to apply"
                    tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                }
                refreshCurrent(tvCurMin, tvCurMax)
            }
        }

        refreshCurrent(tvCurMin, tvCurMax)
    }

    private fun refreshCurrent(tvMin: TextView, tvMax: TextView) {
        scope.launch {
            val (min, max) = withContext(Dispatchers.IO) {
                Pair(RootHelper.getGpuMinFreq(), RootHelper.getGpuMaxFreq())
            }
            tvMin.text = if (min > 0) "${min / 1000000} MHz" else "N/A"
            tvMax.text = if (max > 0) "${max / 1000000} MHz" else "N/A"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
