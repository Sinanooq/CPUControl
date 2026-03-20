package com.cpucontrol

import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class AudioFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loudnessEnhancer: LoudnessEnhancer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_audio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val am = requireContext().getSystemService(AudioManager::class.java)
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        val seekMedia  = view.findViewById<SeekBar>(R.id.seekMedia)
        val seekRing   = view.findViewById<SeekBar>(R.id.seekRing)
        val seekAlarm  = view.findViewById<SeekBar>(R.id.seekAlarm)
        val seekBoost  = view.findViewById<SeekBar>(R.id.seekGain)
        val tvMedia    = view.findViewById<TextView>(R.id.tvMediaVal)
        val tvRing     = view.findViewById<TextView>(R.id.tvRingVal)
        val tvAlarm    = view.findViewById<TextView>(R.id.tvAlarmVal)
        val tvBoost    = view.findViewById<TextView>(R.id.tvGainVal)
        val tvStatus   = view.findViewById<TextView>(R.id.tvGainStatus)
        val btnApply   = view.findViewById<MaterialButton>(R.id.btnGainApply)
        val btnReset   = view.findViewById<MaterialButton>(R.id.btnGainReset)

        // ── Sistem ses seviyeleri ──────────────────────────────────────────
        fun pct(stream: Int): Int {
            val cur = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            return if (max > 0) cur * 100 / max else 0
        }
        fun applyPct(stream: Int, pct: Int) {
            val max = am.getStreamMaxVolume(stream)
            am.setStreamVolume(stream, (pct * max / 100).coerceIn(0, max), 0)
        }

        seekMedia.progress = pct(AudioManager.STREAM_MUSIC)
        seekRing.progress  = pct(AudioManager.STREAM_RING)
        seekAlarm.progress = pct(AudioManager.STREAM_ALARM)
        tvMedia.text = "${seekMedia.progress}%"
        tvRing.text  = "${seekRing.progress}%"
        tvAlarm.text = "${seekAlarm.progress}%"

        fun simpleListener(stream: Int, tv: TextView) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$p%"
                if (fromUser) applyPct(stream, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekMedia.setOnSeekBarChangeListener(simpleListener(AudioManager.STREAM_MUSIC, tvMedia))
        seekRing.setOnSeekBarChangeListener(simpleListener(AudioManager.STREAM_RING, tvRing))
        seekAlarm.setOnSeekBarChangeListener(simpleListener(AudioManager.STREAM_ALARM, tvAlarm))

        // ── Yazılım Amplifikasyonu (LoudnessEnhancer) ─────────────────────
        // Slider: 0–150 → %100 ile %250 arası
        // 0 = normal (%100), 150 = maksimum boost (%250)
        // LoudnessEnhancer gain: milli-dB (1500 milli-dB ≈ +1.5 dB ≈ %150 amplifikasyon)
        // Ama biz 0–1500 milli-dB aralığını 0–150 slider'a map ediyoruz
        val savedBoost = prefs.getInt("audio_boost_pct", 0)
        seekBoost.progress = savedBoost
        tvBoost.text = boostLabel(savedBoost)

        // LoudnessEnhancer'ı başlat (session 0 = global medya)
        initEnhancer(savedBoost)

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvBoost.text = boostLabel(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnApply.setOnClickListener {
            val boost = seekBoost.progress
            scope.launch {
                tvStatus.text = "Uygulanıyor..."
                val ok = withContext(Dispatchers.IO) {
                    applyBoost(boost)
                }
                if (ok) {
                    prefs.edit().putInt("audio_boost_pct", boost).apply()
                    tvStatus.text = "✓ Uygulandı — ${boostLabel(boost)}"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                } else {
                    tvStatus.text = "✗ Efekt uygulanamadı"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        btnReset.setOnClickListener {
            seekBoost.progress = 0
            tvBoost.text = boostLabel(0)
            scope.launch {
                applyBoost(0)
                prefs.edit().putInt("audio_boost_pct", 0).apply()
                tvStatus.text = "✓ Normal seviyeye döndü"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
            }
        }
    }

    /** Slider değerini (0–150) okunabilir etikete çevirir */
    private fun boostLabel(p: Int): String = when {
        p == 0   -> "%100 (Normal)"
        p <= 50  -> "%${100 + p} (+${p})"
        p <= 100 -> "%${100 + p} (Güçlü)"
        else     -> "%${100 + p} (Maksimum)"
    }

    /**
     * LoudnessEnhancer ile yazılım amplifikasyonu uygular.
     * session 0 = global audio output — tüm medya sesini etkiler.
     * gain = slider * 10 milli-dB (0–1500 milli-dB)
     */
    private fun initEnhancer(boostPct: Int) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(0).apply {
                setTargetGain(boostPct * 10) // milli-dB
                enabled = boostPct > 0
            }
        } catch (_: Exception) {}
    }

    private fun applyBoost(boostPct: Int): Boolean {
        return try {
            if (loudnessEnhancer == null) initEnhancer(boostPct)
            loudnessEnhancer?.let {
                it.setTargetGain(boostPct * 10) // 0–1500 milli-dB
                it.enabled = boostPct > 0
            }
            // Root varsa persist property ile de kaydet (reboot sonrası)
            if (boostPct > 0) {
                RootHelper.runAsRoot("setprop persist.audio.volume.boost $boostPct")
            } else {
                RootHelper.runAsRoot("setprop persist.audio.volume.boost 0")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fragment destroy olunca enhancer'ı serbest bırak
        // Ama boost aktifse bırak çalışmaya devam etsin
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        if (prefs.getInt("audio_boost_pct", 0) == 0) {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        }
    }
}
