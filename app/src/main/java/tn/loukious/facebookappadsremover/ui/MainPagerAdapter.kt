package tn.loukious.facebookappadsremover.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()
            1 -> AdBlockerFragment()
            2 -> MediaFragment()
            3 -> AdvancedFragment()
            else -> HomeFragment()
        }
    }
}
