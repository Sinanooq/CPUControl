package com.cpucontrol

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class BloatwareFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: BloatAdapter
    private val allApps = mutableListOf<BloatApp>()

    enum class Filter { ALL, SYSTEM, DISABLED }
    private var currentFilter = Filter.ALL
    private var currentQuery = ""

    data class BloatApp(
        val name: String,
        val pkg: String,
        val icon: android.graphics.drawable.Drawable?,
        val isSystem: Boolean,
        var isDisabled: Boolean
    )

    enum class Risk {
        SAFE,    // Güvenle dondurulabilir
        CAUTION, // Dikkatli ol — bazı özellikler etkilenebilir
        DANGER   // Dondurma — sistem çöküşüne yol açabilir
    }

    data class BloatInfo(val risk: Risk, val reason: String)

    // Paket → (risk seviyesi, açıklama)
    private val knownBloatInfo: Map<String, BloatInfo> = mapOf(
        // ── GÜVENLİ — reklam / analitik / gereksiz ──────────────────────
        "com.miui.analytics"                        to BloatInfo(Risk.SAFE, "Kullanım analitikleri — reklam amaçlı"),
        "com.miui.msa.global"                       to BloatInfo(Risk.SAFE, "MIUI reklam servisi"),
        "com.xiaomi.mipicks"                        to BloatInfo(Risk.SAFE, "Uygulama önerileri / reklam"),
        "com.miui.systemAdSolution"                 to BloatInfo(Risk.SAFE, "Sistem içi reklam çözümü"),
        "com.miui.hybrid"                           to BloatInfo(Risk.SAFE, "MIUI hibrit reklam motoru"),
        "com.miui.hybrid.accessory"                 to BloatInfo(Risk.SAFE, "Reklam motoru eklentisi"),
        "com.miui.bugreport"                        to BloatInfo(Risk.SAFE, "Hata raporu — Xiaomi'ye veri gönderir"),
        "com.miui.klo.bugreport"                    to BloatInfo(Risk.SAFE, "Çekirdek log raporu"),
        "com.miui.cloudbackup"                      to BloatInfo(Risk.SAFE, "Bulut yedekleme — yerel yedek kullanıyorsan gereksiz"),
        "com.miui.cloudservice"                     to BloatInfo(Risk.SAFE, "Mi Cloud servisi"),
        "com.miui.cloudservice.sysbase"             to BloatInfo(Risk.SAFE, "Mi Cloud temel bileşeni"),
        "com.xiaomi.payment"                        to BloatInfo(Risk.SAFE, "Mi Pay — kullanmıyorsan gereksiz"),
        "com.xiaomi.channel"                        to BloatInfo(Risk.SAFE, "Xiaomi içerik kanalı"),
        "com.xiaomi.gamecenter"                     to BloatInfo(Risk.SAFE, "Xiaomi oyun merkezi"),
        "com.xiaomi.gamecenter.sdk.service"         to BloatInfo(Risk.SAFE, "Oyun merkezi SDK servisi"),
        "com.xiaomi.market"                         to BloatInfo(Risk.SAFE, "Mi Market uygulama mağazası"),
        "com.miui.videoplayer"                      to BloatInfo(Risk.SAFE, "MIUI video oynatıcı"),
        "com.miui.player"                           to BloatInfo(Risk.SAFE, "MIUI müzik oynatıcı"),
        "com.miui.notes"                            to BloatInfo(Risk.SAFE, "MIUI notlar uygulaması"),
        "com.miui.compass"                          to BloatInfo(Risk.SAFE, "Pusula uygulaması"),
        "com.miui.fm"                               to BloatInfo(Risk.SAFE, "FM radyo"),
        "com.miui.voiceassist"                      to BloatInfo(Risk.SAFE, "Sesli asistan"),
        "com.miui.personalassistant"                to BloatInfo(Risk.SAFE, "Kişisel asistan / öneri ekranı"),
        "com.miui.newhome"                          to BloatInfo(Risk.SAFE, "Yeni başlangıç ekranı servisi"),
        "com.miui.contentextension"                 to BloatInfo(Risk.SAFE, "İçerik öneri motoru"),
        "com.miui.yellowpage"                       to BloatInfo(Risk.SAFE, "Sarı sayfalar / iş rehberi"),
        "com.miui.translation.youdao"               to BloatInfo(Risk.SAFE, "Youdao çeviri motoru"),
        "com.miui.translation.kingsoft"             to BloatInfo(Risk.SAFE, "Kingsoft çeviri motoru"),
        "com.sohu.inputmethod.sogou.xiaomi"         to BloatInfo(Risk.SAFE, "Sogou klavye (Çince)"),
        "com.iflytek.inputmethod.miui"              to BloatInfo(Risk.SAFE, "iFlytek klavye (Çince)"),
        "com.baidu.input_mi"                        to BloatInfo(Risk.SAFE, "Baidu klavye (Çince)"),
        "com.miui.global.systemapp.paymentservice"  to BloatInfo(Risk.SAFE, "Ödeme servisi"),
        "com.miui.qr"                               to BloatInfo(Risk.SAFE, "QR kod tarayıcı"),
        "com.miui.aod"                              to BloatInfo(Risk.SAFE, "Always-on display — kullanmıyorsan gereksiz"),
        "com.miui.wallpaper"                        to BloatInfo(Risk.SAFE, "Duvar kağıdı uygulaması"),
        "com.xiaomi.scanner"                        to BloatInfo(Risk.SAFE, "Xiaomi tarayıcı"),
        "com.miui.antispam"                         to BloatInfo(Risk.SAFE, "Spam engelleme"),
        "com.miui.mishare.connectivity"             to BloatInfo(Risk.SAFE, "Mi Share bağlantı servisi"),
        "com.miui.miservice"                        to BloatInfo(Risk.SAFE, "Mi servis merkezi"),

        // ── DİKKATLİ — bazı özellikler etkilenebilir ────────────────────
        "com.miui.securityadd"                      to BloatInfo(Risk.CAUTION, "Güvenlik eklentisi — bazı izin kontrolleri etkilenebilir"),
        "com.miui.translationservice"               to BloatInfo(Risk.CAUTION, "Sistem çeviri servisi — ekran çevirisi çalışmaz"),
        "com.miui.screenrecorder"                   to BloatInfo(Risk.CAUTION, "Ekran kaydedici — sistem entegrasyonu var"),
        "com.miui.backup"                           to BloatInfo(Risk.CAUTION, "Yedekleme servisi — yerel yedek de etkilenebilir"),
        "com.miui.catcherpatch"                     to BloatInfo(Risk.CAUTION, "Çökme yakalayıcı — hata raporları çalışmaz"),
        "com.miui.rom.patch"                        to BloatInfo(Risk.CAUTION, "ROM yama servisi — OTA güncellemeleri etkilenebilir"),
        "com.xiaomi.simactivate.service"            to BloatInfo(Risk.CAUTION, "SIM aktivasyon — yeni SIM takınca sorun çıkabilir"),
        "com.miui.calculator"                       to BloatInfo(Risk.CAUTION, "Hesap makinesi — sistem entegrasyonu var"),
        "com.miui.gallery"                          to BloatInfo(Risk.CAUTION, "MIUI galeri — kamera ile entegre, fotoğraf açılmayabilir"),
        "com.miui.cleanmaster"                      to BloatInfo(Risk.CAUTION, "Temizleyici — RAM yönetimi etkilenebilir"),

        // ── TEHLİKELİ — dondurma ────────────────────────────────────────
        "com.miui.securitycenter"                   to BloatInfo(Risk.DANGER, "Güvenlik merkezi — izin yönetimi, uygulama kilitleme çalışmaz"),
        "com.miui.daemon"                           to BloatInfo(Risk.DANGER, "MIUI çekirdek daemon — sistem kararsız hale gelebilir"),
        "com.miui.powerkeeper"                      to BloatInfo(Risk.DANGER, "Güç yöneticisi — pil optimizasyonu ve termal kontrol durur"),
        "com.miui.cit"                              to BloatInfo(Risk.DANGER, "Donanım test aracı — fabrika reset sonrası sorun çıkabilir"),
        "com.xiaomi.xmsf"                           to BloatInfo(Risk.DANGER, "Xiaomi push servisi — tüm Mi bildirimleri durur, bazı uygulamalar çöker")
    )

    private val knownBloat get() = knownBloatInfo.keys

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_bloatware, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv           = view.findViewById<RecyclerView>(R.id.rvBloatware)
        val etSearch     = view.findViewById<EditText>(R.id.etBloatSearch)
        val tvCount      = view.findViewById<TextView>(R.id.tvBloatCount)
        val btnAll       = view.findViewById<MaterialButton>(R.id.btnFilterAll)
        val btnSystem    = view.findViewById<MaterialButton>(R.id.btnFilterSystem)
        val btnDisabled  = view.findViewById<MaterialButton>(R.id.btnFilterDisabled)

        adapter = BloatAdapter(mutableListOf())
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // Arama
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString() ?: ""
                applyFilter(tvCount)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filtre butonları
        fun setFilter(f: Filter) {
            currentFilter = f
            val activeColor = requireContext().getColor(R.color.accent_cyan)
            val inactiveColor = requireContext().getColor(R.color.bg_elevated)
            val activeText = requireContext().getColor(R.color.on_primary)
            val inactiveText = requireContext().getColor(R.color.text_secondary)
            btnAll.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (f == Filter.ALL) activeColor else inactiveColor)
            btnAll.setTextColor(if (f == Filter.ALL) activeText else inactiveText)
            btnSystem.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (f == Filter.SYSTEM) activeColor else inactiveColor)
            btnSystem.setTextColor(if (f == Filter.SYSTEM) activeText else inactiveText)
            btnDisabled.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (f == Filter.DISABLED) requireContext().getColor(R.color.accent_orange) else inactiveColor)
            btnDisabled.setTextColor(if (f == Filter.DISABLED) activeText else
                requireContext().getColor(R.color.accent_orange))
            applyFilter(tvCount)
        }

        btnAll.setOnClickListener { setFilter(Filter.ALL) }
        btnSystem.setOnClickListener { setFilter(Filter.SYSTEM) }
        btnDisabled.setOnClickListener { setFilter(Filter.DISABLED) }

        // Uygulamaları yükle
        tvCount.text = "Yükleniyor..."
        scope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps() }
            allApps.clear()
            allApps.addAll(apps)
            applyFilter(tvCount)
        }
    }

    private fun applyFilter(tvCount: TextView) {
        val filtered = allApps.filter { app ->
            val matchesQuery = currentQuery.isBlank() ||
                app.name.contains(currentQuery, ignoreCase = true) ||
                app.pkg.contains(currentQuery, ignoreCase = true)
            val matchesFilter = when (currentFilter) {
                Filter.ALL      -> true
                Filter.SYSTEM   -> app.isSystem
                Filter.DISABLED -> app.isDisabled
            }
            matchesQuery && matchesFilter
        }.toMutableList()

        adapter.update(filtered)
        tvCount.text = "${filtered.size} uygulama"
    }

    private fun loadApps(): List<BloatApp> {
        val pm = requireContext().packageManager
        val allPkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return allPkgs.map { info ->
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isDisabled = info.flags and ApplicationInfo.FLAG_INSTALLED == 0 ||
                             info.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                             info.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER

            BloatApp(
                name       = pm.getApplicationLabel(info).toString(),
                pkg        = info.packageName,
                icon       = try { pm.getApplicationIcon(info.packageName) } catch (_: Exception) { null },
                isSystem   = isSystem,
                isDisabled = isDisabled
            )
        }
        .filter { it.isSystem || it.pkg in knownBloat } // sadece sistem + bilinen bloat
        .sortedWith(compareByDescending<BloatApp> { it.pkg in knownBloat }.thenBy { it.name.lowercase() })
    }

    inner class BloatAdapter(private val items: MutableList<BloatApp>) :
        RecyclerView.Adapter<BloatAdapter.VH>() {

        fun update(newItems: MutableList<BloatApp>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon   = v.findViewById<ImageView>(R.id.ivBloatIcon)
            val name   = v.findViewById<TextView>(R.id.tvBloatName)
            val pkg    = v.findViewById<TextView>(R.id.tvBloatPkg)
            val status = v.findViewById<TextView>(R.id.tvBloatStatus)
            val btn    = v.findViewById<MaterialButton>(R.id.btnBloatAction)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_bloatware, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = items[position]
            val ctx = holder.itemView.context
            val info = knownBloatInfo[app.pkg]

            holder.name.text = app.name
            holder.pkg.text  = app.pkg
            holder.icon.setImageDrawable(app.icon)

            // Risk rengi + açıklama
            when (info?.risk) {
                Risk.SAFE -> {
                    holder.name.setTextColor(ctx.getColor(R.color.text_primary))
                    holder.pkg.text = "✅ Güvenli  ·  ${info.reason}"
                    holder.pkg.setTextColor(ctx.getColor(R.color.accent_green))
                }
                Risk.CAUTION -> {
                    holder.name.setTextColor(ctx.getColor(R.color.accent_yellow))
                    holder.pkg.text = "⚠  Dikkatli  ·  ${info.reason}"
                    holder.pkg.setTextColor(ctx.getColor(R.color.accent_yellow))
                }
                Risk.DANGER -> {
                    holder.name.setTextColor(ctx.getColor(R.color.accent_orange))
                    holder.pkg.text = "🚫 Tehlikeli  ·  ${info.reason}"
                    holder.pkg.setTextColor(ctx.getColor(R.color.accent_orange))
                }
                null -> {
                    // Listede olmayan sistem uygulaması
                    holder.name.setTextColor(ctx.getColor(R.color.text_primary))
                    holder.pkg.text = app.pkg
                    holder.pkg.setTextColor(ctx.getColor(R.color.text_hint))
                }
            }

            updateRowState(holder, app, info?.risk)

            holder.btn.setOnClickListener {
                // Tehlikeli uygulamalar için onay iste
                if (info?.risk == Risk.DANGER && !app.isDisabled) {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("⚠ Tehlikeli İşlem")
                        .setMessage("${app.name} sistem için kritik olabilir.\n\n${info.reason}\n\nYine de dondurmak istiyor musun?")
                        .setPositiveButton("Dondur") { _, _ -> toggleApp(app, holder, info.risk) }
                        .setNegativeButton("İptal", null)
                        .show()
                } else {
                    toggleApp(app, holder, info?.risk)
                }
            }
        }

        private fun toggleApp(app: BloatApp, holder: VH, risk: Risk?) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    if (app.isDisabled) enableApp(app.pkg) else disableApp(app.pkg)
                }
                if (ok) {
                    app.isDisabled = !app.isDisabled
                    updateRowState(holder, app, risk)
                } else {
                    Toast.makeText(holder.itemView.context, "Başarısız — root gerekli", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun updateRowState(holder: VH, app: BloatApp, risk: Risk? = null) {
            val ctx = holder.itemView.context
            if (app.isDisabled) {
                holder.status.text = "DONDURULMUŞ"
                holder.status.setTextColor(ctx.getColor(R.color.accent_orange))
                holder.status.setBackgroundColor(ctx.getColor(R.color.accent_orange_dim))
                holder.btn.text = "Aktif Et"
                holder.btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ctx.getColor(R.color.accent_green))
                holder.btn.setTextColor(ctx.getColor(R.color.on_primary))
            } else {
                holder.status.text = when (risk) {
                    Risk.DANGER  -> "⚠ KRİTİK"
                    Risk.CAUTION -> "DİKKAT"
                    Risk.SAFE    -> "GÜVENLİ"
                    null         -> "AKTİF"
                }
                holder.status.setTextColor(ctx.getColor(when (risk) {
                    Risk.DANGER  -> R.color.accent_orange
                    Risk.CAUTION -> R.color.accent_yellow
                    Risk.SAFE    -> R.color.accent_green
                    null         -> R.color.text_secondary
                }))
                holder.status.setBackgroundColor(ctx.getColor(when (risk) {
                    Risk.DANGER  -> R.color.accent_orange_dim
                    Risk.CAUTION -> R.color.accent_yellow_dim
                    Risk.SAFE    -> R.color.accent_green_dim
                    null         -> R.color.bg_elevated
                }))
                // Tehlikeli uygulamalarda dondur butonu soluk göster
                val btnColor = if (risk == Risk.DANGER)
                    ctx.getColor(R.color.accent_orange) else ctx.getColor(R.color.accent_orange)
                holder.btn.text = "Dondur"
                holder.btn.backgroundTintList = android.content.res.ColorStateList.valueOf(btnColor)
                holder.btn.setTextColor(ctx.getColor(R.color.on_primary))
                holder.btn.alpha = if (risk == Risk.DANGER) 0.7f else 1f
            }
        }
    }

    /** pm disable-user — uygulamayı sil değil, dondur (geri alınabilir) */
    private fun disableApp(pkg: String): Boolean {
        val (ok, _) = RootHelper.runAsRoot("pm disable-user --user 0 $pkg")
        return ok
    }

    /** pm enable — dondurulmuş uygulamayı geri aç */
    private fun enableApp(pkg: String): Boolean {
        val (ok, _) = RootHelper.runAsRoot("pm enable $pkg")
        return ok
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
