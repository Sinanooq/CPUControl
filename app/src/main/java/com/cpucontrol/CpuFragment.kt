package com.cpucontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class CpuFragment : Fragment() {

    companion object {
        private const val ARG_CPU = "cpu_index"
        private const val ARG_TYPE = "cpu_type" // "little" | "big" | "prime"

        fun newInstance(cpuIndex: Int, cpuType: String): CpuFragment {
            return CpuFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_CPU, cpuIndex)
                    putString(ARG_TYPE, cpuType)
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var cpuIndex = 0
    private var cpuType = "little"

    private val freqs get() = when (cpuType) {
        "big"   -> RootHelper.BIG_FREQS
        "prime" -> RootHelper.PRIME_FREQS
        else    -> RootHelper.LITTLE_FREQS
    }

    private val prefMin get() = "cpu${cpuIndex}_min"
    private val prefMax get() = "cpu${cpuIndex}_max"

    private val defaultMin get() = freqs.first()
    private val defaultMax get() = freqs.last()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cpuIndex = arguments?.getInt(ARG_CPU) ?: 0
        cpuType  = arguments?.getString(ARG_TYPE) ?: "little"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cpu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvTitle    = view.findViewById<TextView>(R.id.tvCoreTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvCoreSubtitle)
        val tvCurMin   = view.findViewById<TextView>(R.id.tvCurrentMin)
        val tvCurMax   = view.findViewById<TextView>(R.id.tvCurrentMax)
        val tvMinSel   = view.findViewById<TextView>(R.id.tvMinSelected)
        val tvMaxSel   = view.findViewById<TextView>(R.id.tvMaxSelected)
        val seekMin    = view.findViewById<SeekBar>(R.id.seekMin)
        val seekMax    = view.findViewById<SeekBar>(R.id.seekMax)
        val tvMinLow   = view.findViewById<TextView>(R.id.tvMinLow)
        val tvMinHigh  = view.findViewById<TextView>(R.id.tvMinHigh)
        val tvMaxLow   = view.findViewById<TextView>(R.id.tvMaxLow)
        val tvMaxHigh  = view.findViewById<TextView>(R.id.tvMaxHigh)
        val btnApply   = view.findViewById<MaterialButton>(R.id.btnApply)
        val tvStatus   = view.findViewById<TextView>(R.id.tvApplyStatus)

        val subtitle = when (cpuType) {
            "big"   -> "Cortex-A715  •  max 3200 MHz"
            "prime" -> "Cortex-A715 Prime  •  max 3350 MHz"
            else    -> "Cortex-A510  •  max 2200 MHz"
        }
        tvTitle.text    = "cpu$cpuIndex"
        tvSubtitle.text = subtitle

        val minLabel = "${freqs.first() / 1000} MHz"
        val maxLabel = "${freqs.last() / 1000} MHz"
        tvMinLow.text  = minLabel; tvMinHigh.text = maxLabel
        tvMaxLow.text  = minLabel; tvMaxHigh.text = maxLabel

        seekMin.max = freqs.size - 1
        seekMax.max = freqs.size - 1

        // Load saved prefs
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        val savedMin = prefs.getInt(prefMin, defaultMin)
        val savedMax = prefs.getInt(prefMax, defaultMax)
        val minIdx = freqs.indexOf(savedMin).coerceAtLeast(0)
        val maxIdx = freqs.indexOf(savedMax).coerceAtLeast(freqs.size - 1)
        seekMin.progress = minIdx
        seekMax.progress = maxIdx
        tvMinSel.text = "${freqs[minIdx] / 1000} MHz"
        tvMaxSel.text = "${freqs[maxIdx] / 1000} MHz"

        seekMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvMinSel.text = "${freqs[p] / 1000} MHz"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (sb.progress > seekMax.progress) sb.progress = seekMax.progress
            }
        })

        seekMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                tvMaxSel.text = "${freqs[p] / 1000} MHz"
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
                    val r1 = RootHelper.setCpuMinFreq(listOf(cpuIndex), minFreq)
                    val r2 = RootHelper.setCpuMaxFreq(listOf(cpuIndex), maxFreq)
                    r1 && r2
                }
                btnApply.isEnabled = true
                if (ok) {
                    prefs.edit().putInt(prefMin, minFreq).putInt(prefMax, maxFreq).apply()
                    tvStatus.text = "Saved  •  ${minFreq / 1000} – ${maxFreq / 1000} MHz"
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
                Pair(RootHelper.getCpuMinFreq(cpuIndex), RootHelper.getCpuMaxFreq(cpuIndex))
            }
            tvMin.text = if (min > 0) "${min / 1000} MHz" else "N/A"
            tvMax.text = if (max > 0) "${max / 1000} MHz" else "N/A"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
