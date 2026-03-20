package com.cpucontrol

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class AppProfileFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: AppAdapter
    private val allApps = mutableListOf<AppItem>()

    data class AppItem(
        val name: String,
        val pkg: String,
        val icon: android.graphics.drawable.Drawable?
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_app_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv       = view.findViewById<RecyclerView>(R.id.rvApps)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val tvCount  = view.findViewById<TextView>(R.id.tvAppCount)

        adapter = AppAdapter(mutableListOf())
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        tvCount.text = "Uygulamalar yükleniyor..."

        scope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps() }
            allApps.clear()
            allApps.addAll(apps)
            adapter.update(apps.toMutableList())
            tvCount.text = "${apps.size} uygulama"
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) allApps.toMutableList()
        else allApps.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.pkg.contains(query, ignoreCase = true)
        }.toMutableList()
        adapter.update(filtered)
    }

    private fun loadApps(): List<AppItem> {
        val pm = requireContext().packageManager
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // sadece kullanıcı uygulamaları
            .map { info ->
                AppItem(
                    name = pm.getApplicationLabel(info).toString(),
                    pkg  = info.packageName,
                    icon = try { pm.getApplicationIcon(info.packageName) } catch (e: Exception) { null }
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    inner class AppAdapter(private val items: MutableList<AppItem>) :
        RecyclerView.Adapter<AppAdapter.VH>() {

        private val profileLabels = arrayOf("Yok", "Dengeli", "Performans", "Oyun", "Pil Tasarrufu")
        private val profileKeys   = arrayOf(null, "dengeli", "performans", "oyun", "pil")

        fun update(newItems: MutableList<AppItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon    = v.findViewById<ImageView>(R.id.ivAppIcon)
            val name    = v.findViewById<TextView>(R.id.tvAppName)
            val pkg     = v.findViewById<TextView>(R.id.tvAppPkg)
            val spinner = v.findViewById<Spinner>(R.id.spinnerProfile)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_profile, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val prefs = requireContext().getSharedPreferences("cpu_prefs", 0)

            holder.name.text = item.name
            holder.pkg.text  = item.pkg
            holder.icon.setImageDrawable(item.icon)

            val spinnerAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                profileLabels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            holder.spinner.adapter = spinnerAdapter

            // Kayıtlı profili yükle
            val savedKey = prefs.getString("app_profile_${item.pkg}", null)
            val idx = profileKeys.indexOfFirst { it == savedKey }.coerceAtLeast(0)
            holder.spinner.setSelection(idx, false)

            holder.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val key = profileKeys[pos]
                    if (key == null) {
                        prefs.edit().remove("app_profile_${item.pkg}").apply()
                    } else {
                        prefs.edit().putString("app_profile_${item.pkg}", key).apply()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
