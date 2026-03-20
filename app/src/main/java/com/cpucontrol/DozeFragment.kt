package com.cpucontrol

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class DozeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Varsayılan istisna paketleri
    private val quickPackages = mapOf(
        "WhatsApp" to "com.whatsapp",
        "Telegram" to "org.telegram.messenger",
        "Telefon"  to "com.android.phone"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_doze, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tvState       = view.findViewById<TextView>(R.id.tvDozeState)
        val tvStatus      = view.findViewById<TextView>(R.id.tvDozeStatus)
        val btnEnable     = view.findViewById<MaterialButton>(R.id.btnDozeEnable)
        val btnDisable    = view.findViewById<MaterialButton>(R.id.btnDozeDisable)
        val btnWhatsapp   = view.findViewById<MaterialButton>(R.id.btnAddWhatsapp)
        val btnTelegram   = view.findViewById<MaterialButton>(R.id.btnAddTelegram)
        val btnPhone      = view.findViewById<MaterialButton>(R.id.btnAddPhone)
        val etPkg         = view.findViewById<EditText>(R.id.etPackageName)
        val btnAdd        = view.findViewById<MaterialButton>(R.id.btnAddException)
        val btnRefresh    = view.findViewById<MaterialButton>(R.id.btnRefreshList)
        val llExceptions  = view.findViewById<LinearLayout>(R.id.llExceptions)
        val tvNoExceptions= view.findViewById<TextView>(R.id.tvNoExceptions)

        // Mevcut Doze durumunu göster
        refreshDozeState(tvState)

        // Doze aktif et
        btnEnable.setOnClickListener {
            scope.launch {
                tvStatus.text = "Uygulanıyor..."
                val ok = withContext(Dispatchers.IO) {
                    RootHelper.runAsRoot("dumpsys deviceidle force-idle deep").first
                }
                if (ok) {
                    tvStatus.text = "✓ Doze aktif — derin uyku modu"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                    tvState.text = "AKTİF"
                    tvState.setTextColor(requireContext().getColor(R.color.accent_green))
                    tvState.setBackgroundColor(requireContext().getColor(R.color.accent_green_dim))
                } else {
                    tvStatus.text = "✗ Başarısız — root gerekli"
                    tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
                }
            }
        }

        // Doze devre dışı
        btnDisable.setOnClickListener {
            scope.launch {
                withContext(Dispatchers.IO) {
                    RootHelper.runAsRoot("dumpsys deviceidle unforce")
                    RootHelper.runAsRoot("dumpsys deviceidle disable")
                }
                tvStatus.text = "✓ Doze devre dışı"
                tvStatus.setTextColor(requireContext().getColor(R.color.text_secondary))
                tvState.text = "KAPALI"
                tvState.setTextColor(requireContext().getColor(R.color.text_secondary))
                tvState.setBackgroundColor(requireContext().getColor(R.color.bg_elevated))
            }
        }

        // Hızlı ekle butonları
        btnWhatsapp.setOnClickListener { addException("com.whatsapp", tvStatus, llExceptions, tvNoExceptions) }
        btnTelegram.setOnClickListener { addException("org.telegram.messenger", tvStatus, llExceptions, tvNoExceptions) }
        btnPhone.setOnClickListener    { addException("com.android.phone", tvStatus, llExceptions, tvNoExceptions) }

        // Manuel ekle
        btnAdd.setOnClickListener {
            val pkg = etPkg.text.toString().trim()
            if (pkg.isNotEmpty()) {
                addException(pkg, tvStatus, llExceptions, tvNoExceptions)
                etPkg.setText("")
            } else {
                tvStatus.text = "Paket adı boş olamaz"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
            }
        }

        // Listeyi yenile
        btnRefresh.setOnClickListener {
            refreshExceptionList(llExceptions, tvNoExceptions)
        }

        // İlk yükleme
        refreshExceptionList(llExceptions, tvNoExceptions)
    }

    private fun refreshDozeState(tvState: TextView) {
        scope.launch {
            val state = withContext(Dispatchers.IO) {
                val (_, out) = RootHelper.runAsRoot("dumpsys deviceidle get deep")
                out.trim()
            }
            when {
                state.contains("IDLE", ignoreCase = true) -> {
                    tvState.text = "AKTİF"
                    tvState.setTextColor(requireContext().getColor(R.color.accent_green))
                    tvState.setBackgroundColor(requireContext().getColor(R.color.accent_green_dim))
                }
                state.contains("ACTIVE", ignoreCase = true) -> {
                    tvState.text = "KAPALI"
                    tvState.setTextColor(requireContext().getColor(R.color.text_secondary))
                    tvState.setBackgroundColor(requireContext().getColor(R.color.bg_elevated))
                }
                else -> {
                    tvState.text = state.ifEmpty { "—" }
                    tvState.setTextColor(requireContext().getColor(R.color.text_secondary))
                    tvState.setBackgroundColor(requireContext().getColor(R.color.bg_elevated))
                }
            }
        }
    }

    private fun addException(
        pkg: String,
        tvStatus: TextView,
        llExceptions: LinearLayout,
        tvNoExceptions: TextView
    ) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                RootHelper.runAsRoot("dumpsys deviceidle whitelist +$pkg").first
            }
            if (ok) {
                tvStatus.text = "✓ $pkg istisna listesine eklendi"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_green))
                refreshExceptionList(llExceptions, tvNoExceptions)
            } else {
                tvStatus.text = "✗ Eklenemedi: $pkg"
                tvStatus.setTextColor(requireContext().getColor(R.color.accent_orange))
            }
        }
    }

    private fun refreshExceptionList(llExceptions: LinearLayout, tvNoExceptions: TextView) {
        scope.launch {
            val packages = withContext(Dispatchers.IO) { getWhitelist() }

            // Mevcut satırları temizle (tvNoExceptions hariç)
            val toRemove = (0 until llExceptions.childCount)
                .map { llExceptions.getChildAt(it) }
                .filter { it.id != R.id.tvNoExceptions }
            toRemove.forEach { llExceptions.removeView(it) }

            if (packages.isEmpty()) {
                tvNoExceptions.visibility = View.VISIBLE
                return@launch
            }

            tvNoExceptions.visibility = View.GONE

            packages.forEach { pkg ->
                val row = buildExceptionRow(pkg, llExceptions, tvNoExceptions)
                llExceptions.addView(row)
            }
        }
    }

    private fun buildExceptionRow(
        pkg: String,
        llExceptions: LinearLayout,
        tvNoExceptions: TextView
    ): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }

        // Paket adı
        val tvPkg = TextView(ctx).apply {
            text = pkg
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Kaldır butonu
        val btnRemove = MaterialButton(ctx).apply {
            text = "Kaldır"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.accent_orange))
            setBackgroundColor(ctx.getColor(android.R.color.transparent))
            strokeColor = android.content.res.ColorStateList.valueOf(
                ctx.getColor(R.color.accent_orange_dim))
            strokeWidth = 1
            cornerRadius = 8
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        RootHelper.runAsRoot("dumpsys deviceidle whitelist -$pkg")
                    }
                    refreshExceptionList(llExceptions, tvNoExceptions)
                }
            }
        }

        row.addView(tvPkg)
        row.addView(btnRemove)
        return row
    }

    /**
     * `dumpsys deviceidle whitelist` çıktısını parse eder.
     * Çıktı formatı:
     *   Whitelist (except idle) system apps:
     *     com.android.phone
     *   Whitelist user apps:
     *     com.whatsapp
     */
    private fun getWhitelist(): List<String> {
        val (_, out) = RootHelper.runAsRoot("dumpsys deviceidle whitelist")
        return out.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                !line.startsWith("Whitelist") &&
                !line.startsWith("system-exem") &&
                line.contains(".") &&
                !line.contains(":") &&
                !line.contains("=")
            }
            .distinct()
            .sorted()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
