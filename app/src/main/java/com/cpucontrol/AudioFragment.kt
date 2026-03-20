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
    private val enhancers = mutableListOf<LoudnessEnhancer>()

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

        if (savedBoost > 0) {
            scope.launch { withContext(Dispatchers.IO) { applyBoost(requireContext(), savedBoost) } }
        }

        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvBoost.text = boostLabel(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnApply.setOnClickListener {
            val boost = seekBoost.progress
            scope.launch {
                tvStatus.text = "Uygulanıyor..."
                val ok = withContext(Dispatchers.IO) { applyBoost(requireContext(), boost) }
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
                withContext(Dispatchers.IO) { applyBoost(requireContext(), 0) }
                prefs.edit().putInt("audio_boost_pct", 0).apply()
                tvStatus.text = "✓ Normal seviyeye döndü"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
            }
        }
    }

    private fun boostLabel(p: Int): String = when {
        p == 0   -> "%100 (Normal)"
        p <= 50  -> "%${100 + p} (+$p)"
        p <= 100 -> "%${100 + p} (Güçlü)"
        else     -> "%${100 + p} (Maksimum)"
    }

    private fun applyBoost(ctx: Context, boostPct: Int): Boolean {
        // 1. Mevcut enhancer'ları temizle
        enhancers.forEach { runCatching { it.release() } }
        enhancers.clear()

        if (boostPct == 0) {
            // Root ile de sıfırla
            RootHelper.runAsRoot("setprop persist.audio.volume.boost 0")
            return true
        }

        val gainMilliDb = (boostPct * 10).coerceIn(0, 1500) // 0–1500 milli-dB

        // 2. Tüm aktif audio session'larına uygula
        val am = ctx.getSystemService(AudioManager::class.java)
        var applied = false

        // Aktif session ID'lerini al (Android 9+)
        try {
            val sessions = am.generateAudioSessionId()
            // Global session (0) + yeni session
            for (sessionId in listOf(0, sessions)) {
                try {
                    val le = LoudnessEnhancer(sessionId)
                    le.setTargetGain(gainMilliDb)
                    le.enabled = true
                    enhancers.add(le)
                    applied = true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 3. Root ile ALSA/tinymix üzerinden hardware gain (cihaza göre değişir)
        val rootCmds = listOf(
            // Genel yaklaşım: media server volume
            "setprop persist.audio.volume.boost $boostPct",
            // Bazı Qualcomm/MTK cihazlarda çalışan yollar
            "tinymix 'Speaker Gain' $boostPct 2>/dev/null || true",
            "tinymix 'RX3 Digital Volume' $boostPct 2>/dev/null || true",
            "tinymix 'HPOUT1L Digital' $boostPct 2>/dev/null || true"
        )
        rootCmds.forEach { RootHelper.runAsRoot(it) }

        return applied
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)
        if (prefs.getInt("audio_boost_pct", 0) == 0) {
            enhancers.forEach { runCatching { it.release() } }
            enhancers.clear()
        }
    }
}

