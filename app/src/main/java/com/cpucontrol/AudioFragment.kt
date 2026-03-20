package com.cpucontrol

import android.content.Context
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
    private var enhancer: LoudnessEnhancer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_audio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val am    = requireContext().getSystemService(AudioManager::class.java)
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

        val seekMedia = view.findViewById<SeekBar>(R.id.seekMedia)
        val seekRing  = view.findViewById<SeekBar>(R.id.seekRing)
        val seekAlarm = view.findViewById<SeekBar>(R.id.seekAlarm)
        val seekBoost = view.findViewById<SeekBar>(R.id.seekGain)
        val tvMedia   = view.findViewById<TextView>(R.id.tvMediaVal)
        val tvRing    = view.findViewById<TextView>(R.id.tvRingVal)
        val tvAlarm   = view.findViewById<TextView>(R.id.tvAlarmVal)
        val tvBoost   = view.findViewById<TextView>(R.id.tvGainVal)
        val tvStatus  = view.findViewById<TextView>(R.id.tvGainStatus)
        val btnApply  = view.findViewById<MaterialButton>(R.id.btnGainApply)
        val btnReset  = view.findViewById<MaterialButton>(R.id.btnGainReset)

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

        // ── Yazılım Amplifikasyonu ─────────────────────────────────────────
        val savedBoost = prefs.getInt("audio_boost_pct", 0)
        seekBoost.progress = savedBoost
        tvBoost.text = boostLabel(savedBoost)

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvBoost.text = boostLabel(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnApply.setOnClickListener {
            val boost = seekBoost.progress
            btnApply.isEnabled = false
            scope.launch {
                tvStatus.text = "Uygulanıyor..."
                tvStatus.setTextColor(requireContext().getColor(R.color.text_secondary))
                val result = withContext(Dispatchers.IO) { applyBoost(requireContext(), am, boost) }
                btnApply.isEnabled = true
                prefs.edit().putInt("audio_boost_pct", boost).apply()
                tvStatus.text = result
                tvStatus.setTextColor(requireContext().getColor(
                    if (result.startsWith("✓")) R.color.accent_green else R.color.accent_orange
                ))
            }
        }

        btnReset.setOnClickListener {
            seekBoost.progress = 0
            tvBoost.text = boostLabel(0)
            scope.launch {
                withContext(Dispatchers.IO) { applyBoost(requireContext(), am, 0) }
                prefs.edit().putInt("audio_boost_pct", 0).apply()
                tvStatus.text = "✓ Normal seviyeye döndü"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
            }
        }

        // Kaydedilmiş boost varsa uygula
        if (savedBoost > 0) {
            scope.launch { withContext(Dispatchers.IO) { applyBoost(requireContext(), am, savedBoost) } }
        }
    }

    private fun boostLabel(p: Int): String = when {
        p == 0   -> "%100 (Normal)"
        p <= 50  -> "%${100 + p} (+$p)"
        p <= 100 -> "%${100 + p} (Güçlü)"
        else     -> "%${100 + p} (Maksimum)"
    }

    private fun applyBoost(ctx: Context, am: AudioManager, boostPct: Int): String {
        // Mevcut enhancer'ı temizle
        runCatching { enhancer?.release() }
        enhancer = null

        if (boostPct == 0) {
            // Root ile sıfırla
            RootHelper.runAsRoot("setprop persist.audio.volume.boost 0")
            applyTinymixReset()
            return "✓ Sıfırlandı"
        }

        val gainMilliDb = (boostPct * 10).coerceIn(100, 1500)
        val results = mutableListOf<String>()

        // Katman 1: Ses seviyesini max'a çek
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            results.add("volume_max")
        } catch (_: Exception) {}

        // Katman 2: LoudnessEnhancer — AudioEffect global session (0)
        try {
            val le = LoudnessEnhancer(0)
            le.setTargetGain(gainMilliDb)
            le.enabled = true
            enhancer = le
            results.add("loudness_enhancer")
        } catch (e: Exception) {
            // Session 0 çalışmadıysa yeni bir session dene
            try {
                val sessionId = am.generateAudioSessionId()
                val le = LoudnessEnhancer(sessionId)
                le.setTargetGain(gainMilliDb)
                le.enabled = true
                enhancer = le
                results.add("loudness_enhancer_session")
            } catch (_: Exception) {}
        }

        // Katman 3: Root ile tinymix hardware gain (MTK Dimensity için)
        val tinymixResult = applyTinymixGain(boostPct)
        if (tinymixResult) results.add("tinymix_hw")

        // Katman 4: Root ile media volume property
        RootHelper.runAsRoot("setprop persist.audio.volume.boost $boostPct")

        return if (results.isNotEmpty())
            "✓ Uygulandı (${results.joinToString(", ")})"
        else
            "⚠ Kısmi — ${boostLabel(boostPct)}"
    }

    private fun applyTinymixGain(boostPct: Int): Boolean {
        // MTK Dimensity 8300U için yaygın mixer kontrolleri
        // Değer aralığı cihaza göre değişir, 0-255 veya 0-100 olabilir
        val gainVal = (boostPct * 2).coerceIn(0, 255)
        val controls = listOf(
            "Speaker Gain",
            "Speaker Volume",
            "Headphone Volume",
            "HPOUT1L Digital",
            "HPOUT1R Digital",
            "RX3 Digital Volume",
            "RX4 Digital Volume",
            "RX INT7_1 MIX1 INP0",
            "LINEOUT1 Volume",
            "Digital Volume"
        )
        var any = false
        for (ctrl in controls) {
            val (ok, _) = RootHelper.runAsRoot("tinymix '$ctrl' $gainVal 2>/dev/null")
            if (ok) any = true
        }
        return any
    }

    private fun applyTinymixReset() {
        val controls = listOf("Speaker Gain", "Speaker Volume", "Headphone Volume",
            "HPOUT1L Digital", "HPOUT1R Digital", "RX3 Digital Volume", "RX4 Digital Volume")
        for (ctrl in controls) {
            RootHelper.runAsRoot("tinymix '$ctrl' 100 2>/dev/null || true")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        if (prefs.getInt("audio_boost_pct", 0) == 0) {
            runCatching { enhancer?.release() }
            enhancer = null
        }
    }
}
