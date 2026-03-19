package com.cpucontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class AllCpuFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Her çekirdek için tip ve renk
    private data class CoreInfo(
        val cpu: Int,
        val type: String,       // "little" | "big" | "prime"
        val label: String,
        val colorRes: Int
    )

    private val cores = listOf(
        CoreInfo(0, "little", "Cortex-A510", R.color.accent_green),
        CoreInfo(1, "little", "Cortex-A510", R.color.accent_green),
        CoreInfo(2, "little", "Cortex-A510", R.color.accent_green),
        CoreInfo(3, "little", "Cortex-A510", R.color.accent_green),
        CoreInfo(4, "big",    "Cortex-A715", R.color.accent_cyan),
        CoreInfo(5, "big",    "Cortex-A715", R.color.accent_cyan),
        CoreInfo(6, "big",    "Cortex-A715", R.color.accent_cyan),
        CoreInfo(7, "prime",  "A715 Prime",  R.color.accent_purple)
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_all_cpu, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.llCpuContainer)
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        cores.forEach { info ->
            val freqs = when (info.type) {
                "big"   -> RootHelper.BIG_FREQS
                "prime" -> RootHelper.PRIME_FREQS
                else    -> RootHelper.LITTLE_FREQS
            }
            val accentColor = requireContext().getColor(info.colorRes)

            // Kart oluştur
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dpToPx(12) }
                setCardBackgroundColor(requireContext().getColor(R.color.bg_card))
                radius = dpToPx(20).toFloat()
                strokeColor = requireContext().getColor(R.color.divider)
                strokeWidth = dpToPx(1)
                cardElevation = 0f
            }

            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
            }

            // Başlık satırı
            val headerRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val titleCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(requireContext()).apply {
                text = "cpu${info.cpu}"
                textSize = 20f
                setTextColor(requireContext().getColor(R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val tvSub = TextView(requireContext()).apply {
                text = info.label + "  •  maks ${freqs.last() / 1000} MHz"
                textSize = 11f
                setTextColor(requireContext().getColor(R.color.text_secondary))
            }

            titleCol.addView(tvTitle)
            titleCol.addView(tvSub)

            val switchOnline = SwitchMaterial(requireContext()).apply {
                text = "Aktif"
                textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_secondary))
                thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
                trackTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.divider))
            }

            headerRow.addView(titleCol)
            headerRow.addView(switchOnline)

            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                ).also { it.topMargin = dpToPx(16); it.bottomMargin = dpToPx(16) }
                setBackgroundColor(requireContext().getColor(R.color.divider))
            }

            // Mevcut frekans satırı
            val freqRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dpToPx(16) }
            }

            val minBox = makeFreqBox("MİN", accentColor)
            val tvCurMin = minBox.getChildAt(1) as TextView
            val maxBox = makeFreqBox("MAKS", accentColor)
            val tvCurMax = maxBox.getChildAt(1) as TextView

            freqRow.addView(minBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = dpToPx(8) })
            freqRow.addView(maxBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Frekans ayar kartı (seekbar'lar)
            val freqCard = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dpToPx(12) }
                setCardBackgroundColor(requireContext().getColor(R.color.bg_card2))
                radius = dpToPx(12).toFloat()
                cardElevation = 0f
            }

            val freqInner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            }

            // Min seekbar
            val tvMinLabel = TextView(requireContext()).apply {
                text = "MİNİMUM"
                textSize = 9f
                letterSpacing = 0.12f
                setTextColor(accentColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val tvMinVal = TextView(requireContext()).apply {
                textSize = 16f
                setTextColor(requireContext().getColor(R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val seekMin = SeekBar(requireContext()).apply {
                max = freqs.size - 1
                progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
                thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            }

            // Max seekbar
            val tvMaxLabel = TextView(requireContext()).apply {
                text = "MAKSİMUM"
                textSize = 9f
                letterSpacing = 0.12f
                setTextColor(accentColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(12) }
            }
            val tvMaxVal = TextView(requireContext()).apply {
                textSize = 16f
                setTextColor(requireContext().getColor(R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val seekMax = SeekBar(requireContext()).apply {
                max = freqs.size - 1
                progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
                thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            }

            // Kayıtlı değerleri yükle
            val savedMin = prefs.getInt("cpu${info.cpu}_min", freqs.first())
            val savedMax = prefs.getInt("cpu${info.cpu}_max", freqs.last())
            seekMin.progress = freqs.indexOf(savedMin).coerceAtLeast(0)
            seekMax.progress = freqs.indexOf(savedMax).let { if (it < 0) freqs.size - 1 else it }
            tvMinVal.text = "${freqs[seekMin.progress] / 1000} MHz"
            tvMaxVal.text = "${freqs[seekMax.progress] / 1000} MHz"

            seekMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    tvMinVal.text = "${freqs[p] / 1000} MHz"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    if (sb.progress > seekMax.progress) sb.progress = seekMax.progress
                }
            })

            seekMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    tvMaxVal.text = "${freqs[p] / 1000} MHz"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    if (sb.progress < seekMin.progress) sb.progress = seekMin.progress
                }
            })

            freqInner.addView(tvMinLabel)
            freqInner.addView(tvMinVal)
            freqInner.addView(seekMin)
            freqInner.addView(tvMaxLabel)
            freqInner.addView(tvMaxVal)
            freqInner.addView(seekMax)
            freqCard.addView(freqInner)

            // Uygula butonu
            val tvStatus = TextView(requireContext()).apply {
                textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(8) }
            }

            val btnApply = MaterialButton(requireContext()).apply {
                text = "Uygula"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
                setBackgroundColor(accentColor)
                setTextColor(requireContext().getColor(android.R.color.black))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                cornerRadius = dpToPx(12)
            }

            btnApply.setOnClickListener {
                val minFreq = freqs[seekMin.progress]
                val maxFreq = freqs[seekMax.progress]
                btnApply.isEnabled = false
                tvStatus.text = "Uygulanıyor..."

                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        RootHelper.setCpuMinFreq(listOf(info.cpu), minFreq) &&
                        RootHelper.setCpuMaxFreq(listOf(info.cpu), maxFreq)
                    }
                    btnApply.isEnabled = true
                    if (ok) {
                        prefs.edit()
                            .putInt("cpu${info.cpu}_min", minFreq)
                            .putInt("cpu${info.cpu}_max", maxFreq)
                            .apply()
                        tvStatus.text = "Kaydedildi  •  ${minFreq / 1000} – ${maxFreq / 1000} MHz"
                        tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                    } else {
                        tvStatus.text = "Uygulama başarısız"
                        tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                    }
                    // Mevcut frekansları güncelle
                    val (curMin, curMax) = withContext(Dispatchers.IO) {
                        Pair(RootHelper.getCpuMinFreq(info.cpu), RootHelper.getCpuMaxFreq(info.cpu))
                    }
                    tvCurMin.text = if (curMin > 0) "${curMin / 1000} MHz" else "N/A"
                    tvCurMax.text = if (curMax > 0) "${curMax / 1000} MHz" else "N/A"
                }
            }

            // cpu0 kapatılamaz
            if (info.cpu == 0) {
                switchOnline.isChecked = true
                switchOnline.isEnabled = false
                switchOnline.text = "Kapatılamaz"
            } else {
                scope.launch {
                    val online = withContext(Dispatchers.IO) { RootHelper.isCpuOnline(info.cpu) }
                    switchOnline.isChecked = online
                    switchOnline.text = if (online) "Aktif" else "Kapalı"
                    freqCard.alpha = if (online) 1f else 0.4f
                    btnApply.isEnabled = online
                }

                switchOnline.setOnCheckedChangeListener { _, isChecked ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            RootHelper.setCpuOnline(info.cpu, isChecked)
                        }
                        if (ok) {
                            switchOnline.text = if (isChecked) "Aktif" else "Kapalı"
                            freqCard.alpha = if (isChecked) 1f else 0.4f
                            btnApply.isEnabled = isChecked
                        } else {
                            switchOnline.isChecked = !isChecked
                        }
                    }
                }
            }

            // Mevcut frekansları yükle
            scope.launch {
                val (curMin, curMax) = withContext(Dispatchers.IO) {
                    Pair(RootHelper.getCpuMinFreq(info.cpu), RootHelper.getCpuMaxFreq(info.cpu))
                }
                tvCurMin.text = if (curMin > 0) "${curMin / 1000} MHz" else "N/A"
                tvCurMax.text = if (curMax > 0) "${curMax / 1000} MHz" else "N/A"
            }

            inner.addView(headerRow)
            inner.addView(divider)
            inner.addView(freqRow)
            inner.addView(freqCard)
            inner.addView(btnApply)
            inner.addView(tvStatus)
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun makeFreqBox(label: String, color: Int): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(requireContext().getColor(R.color.bg_card2))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))

            addView(TextView(requireContext()).apply {
                text = label
                textSize = 9f
                letterSpacing = 0.15f
                setTextColor(requireContext().getColor(R.color.text_secondary))
            })
            addView(TextView(requireContext()).apply {
                text = "— MHz"
                textSize = 18f
                setTextColor(color)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
