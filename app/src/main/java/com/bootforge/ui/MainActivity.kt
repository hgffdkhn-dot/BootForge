package com.bootforge.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.bootforge.R
import com.bootforge.databinding.ActivityMainBinding
import com.bootforge.vm.WorkViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: WorkViewModel

    private val home = HomeFragment()
    private val inject = InjectFragment()
    private val log = LogFragment()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        vm = ViewModelProvider(this)[WorkViewModel::class.java]

        if (saved == null) show(home)
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(home)
                R.id.nav_inject -> show(inject)
                R.id.nav_log -> show(log)
            }
            true
        }
        binding.bottomNav.setOnItemReselectedListener { /* keep as is */ }

        com.bootforge.util.LogBus.add("BootForge 已启动")
    }

    private fun show(fragment: Fragment) {
        val tag = fragment.javaClass.simpleName
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.forEach { if (it.isVisible) hide(it) }
            if (existing != null) show(existing) else add(R.id.container, fragment, tag)
        }.commit()
    }
}
