package tn.loukious.facebookappadsremover.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import tn.loukious.facebookappadsremover.R
import tn.loukious.facebookappadsremover.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * True once the module app has bound to the framework service
         * (RemotePrefs) — i.e. the module is actually loaded by LSPosed and
         * the remote-preference channel is live. ModuleMain's onPackageReady
         * additionally short-circuits this to TRUE when the framework injects
         * this app's own process.
         */
        @JvmStatic
        fun isXposedEnabled(): Boolean = RemotePrefs.bound
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.isSaveEnabled = false

        binding.navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> binding.viewPager.setCurrentItem(0, true)
                R.id.navigation_adblock -> binding.viewPager.setCurrentItem(1, true)
                R.id.navigation_media -> binding.viewPager.setCurrentItem(2, true)
                R.id.navigation_advanced -> binding.viewPager.setCurrentItem(3, true)
            }
            true
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                binding.navView.menu.getItem(position).isChecked = true
            }
        })

        val root = findViewById<View>(android.R.id.content)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val screenHeight = root.rootView.height
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight > screenHeight * 0.15) {
                binding.navContainer.visibility = View.GONE
            } else {
                binding.navContainer.visibility = View.VISIBLE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.viewPager.currentItem != 0) {
                    binding.viewPager.setCurrentItem(0, true)
                    binding.navView.selectedItemId = R.id.navigation_home
                } else {
                    finish()
                }
            }
        })
    }
}
