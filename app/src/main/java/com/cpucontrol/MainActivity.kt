package com.cpucontrol

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Tab definitions: label, fragment
    private val tabs = listOf(
        "cpu0" to { CpuFragment.newInstance(0, "little") },
        "cpu1" to { CpuFragment.newInstance(1, "little") },
        "cpu2" to { CpuFragment.newInstance(2, "little") },
        "cpu3" to { CpuFragment.newInstance(3, "little") },
        "cpu4" to { CpuFragment.newInstance(4, "big") },
        "cpu5" to { CpuFragment.newInstance(5, "big") },
        "cpu6" to { CpuFragment.newInstance(6, "big") },
        "cpu7" to { CpuFragment.newInstance(7, "prime") },
        "GPU"  to { GpuFragment() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val tvRoot    = findViewById<TextView>(R.id.tvRootStatus)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = tabs[position].second()
        }

        viewPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = tabs[pos].first
        }.attach()

        // Root check
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootHelper.checkRoot() }
            if (ok) {
                tvRoot.text = "● Root izni verildi"
                tvRoot.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                tvRoot.text = "● Root erişimi yok"
                tvRoot.setTextColor(getColor(android.R.color.holo_red_dark))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
