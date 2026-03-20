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
        val btnListMixer = view.findViewById<MaterialButton>(R.id.btnListMixer)
        val tvMixerList  = view.findViewById<TextView>(R.id.tvMixerList)

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

        // Mixer kontrollerini listele — debug için
        btnListMixer.setOnClickListener {
            btnListMixer.isEnabled = false
            tvMixerList.text = "Listeleniyor..."
            scope.launch {
                val (_, out) = withContext(Dispatchers.IO) {
                    RootHelper.runAsRoot("tinymix 2>/dev/null")
                }
                btnListMixer.isEnabled = true
                if (out.isBlank()) {
                    tvMixerList.text = "tinymix bulunamadı veya root yok"
                } else {
                    // Sadece ses/gain/volume içerenleri göster
                    val keywords = listOf("speaker", "spk", "volume", "gain", "playback",
                        "lineout", "hpout", "earpiece", "amp", "pcm", "audio", "rx", "tx")
                    val filtered = out.lines()
                        .filter { line -> keywords.any { line.lowercase().contains(it) } }
                        .take(30)
                        .joinToString("\n")
                    tvMixerList.text = filtered.ifBlank { "Eşleşen kontrol bulunamadı\n\n${out.take(500)}" }
                }
            }
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
            RootHelper.runAsRoot("setprop persist.audio.volume.boost 0")
            restoreTinymix()
            return "✓ Sıfırlandı"
        }

        val results = mutableListOf<String>()

        // Katman 1: Ses seviyesini max'a çek
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            results.add("vol_max")
        } catch (_: Exception) {}

        // Katman 2: tinymix — cihazdan gerçek kontrolleri bul ve uygula
        val tinymixOk = applyTinymixDynamic(boostPct)
        if (tinymixOk) results.add("hw_mixer")

        // Katman 3: LoudnessEnhancer tüm session'lara dene
        val gainMilliDb = (boostPct * 12).coerceIn(100, 1500)
        for (session in listOf(0, 1, am.generateAudioSessionId())) {
            try {
                val le = LoudnessEnhancer(session)
                le.setTargetGain(gainMilliDb)
                le.enabled = true
                if (enhancer == null) enhancer = le else le.release()
                results.add("le_s$session")
                break
            } catch (_: Exception) {}
        }

        // Katman 4: setprop ile persist
        RootHelper.runAsRoot("setprop persist.audio.volume.boost $boostPct")

        return if (results.isNotEmpty())
            "✓ ${results.joinToString(" + ")}"
        else
            "⚠ Etki yok — tinymix bulunamadı"
    }

    private fun applyTinymixDynamic(boostPct: Int): Boolean {
        // Cihazdan tüm mixer kontrollerini listele
        val (ok, out) = RootHelper.runAsRoot("tinymix 2>/dev/null | head -80")
        if (!ok || out.isBlank()) return false

        // Ses/gain/volume içeren satırları bul
        val keywords = listOf("speaker", "spk", "rx volume", "rx gain", "playback volume",
            "digital volume", "lineout", "hpout", "earpiece", "amp gain", "output volume",
            "master volume", "pcm volume", "audio gain")

        val lines = out.lines()
        var applied = false

        for (line in lines) {
            val lower = line.lowercase()
            if (keywords.none { lower.contains(it) }) continue

            // Kontrol adını çıkar — "ID Name Value" formatı
            // tinymix çıktısı: "  0  Speaker Volume          100 (range 0->255)"
            val nameMatch = Regex("^\\s*\\d+\\s+(.+?)\\s{2,}").find(line)
                ?: Regex("^\\s*(.+?)\\s+\\d+").find(line)
            val ctrlName = nameMatch?.groupValues?.get(1)?.trim() ?: continue

            // Mevcut max değeri bul
            val rangeMatch = Regex("range\\s+(\\d+)->\\s*(\\d+)").find(line)
            val maxVal = rangeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 255
            val minVal = rangeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            // Boost oranına göre değer hesapla — max'ın %boostPct üstü
            val targetVal = (minVal + (maxVal - minVal) * (100 + boostPct) / 100).coerceAtMost(maxVal)

            val (setOk, _) = RootHelper.runAsRoot("tinymix '$ctrlName' $targetVal 2>/dev/null")
            if (setOk) applied = true
        }
        return applied
    }

    private fun restoreTinymix() {
        val (_, out) = RootHelper.runAsRoot("tinymix 2>/dev/null | head -80")
        if (out.isBlank()) return
        val keywords = listOf("speaker", "spk", "rx volume", "rx gain", "playback volume",
            "digital volume", "lineout", "hpout", "earpiece", "amp gain", "output volume",
            "master volume", "pcm volume", "audio gain")
        for (line in out.lines()) {
            val lower = line.lowercase()
            if (keywords.none { lower.contains(it) }) continue
            val nameMatch = Regex("^\\s*\\d+\\s+(.+?)\\s{2,}").find(line)
                ?: Regex("^\\s*(.+?)\\s+\\d+").find(line)
            val ctrlName = nameMatch?.groupValues?.get(1)?.trim() ?: continue
            val rangeMatch = Regex("range\\s+(\\d+)->\\s*(\\d+)").find(line)
            val maxVal = rangeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 100
            RootHelper.runAsRoot("tinymix '$ctrlName' $maxVal 2>/dev/null || true")
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
