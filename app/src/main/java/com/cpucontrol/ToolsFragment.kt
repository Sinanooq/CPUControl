package com.cpucontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class ToolsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_tools, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayoutTools)
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPagerTools)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 5
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> BatteryFragment()
                1 -> NetworkFragment()
                2 -> AudioFragment()
                3 -> DozeFragment()
                else -> RamFragment()
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = when (pos) {
                0 -> "Pil"
                1 -> "Ağ"
                2 -> "Ses"
                3 -> "Doze"
                else -> "RAM"
            }
        }.attach()
    }
}
