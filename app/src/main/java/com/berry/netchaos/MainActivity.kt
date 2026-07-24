package com.berry.netchaos

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnApplyCondition: Button
    private lateinit var tvAppliedCondition: TextView
    private lateinit var rgNetworkCondition: RadioGroup
    private lateinit var btnRdOn: Button
    private lateinit var btnRdOff: Button
    private lateinit var tvStatus: TextView

    private var isRandomDisconnectActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var isConnectedCycle = true

    private val vpnPrepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnApplyCondition = findViewById(R.id.btn_apply_condition)
        tvAppliedCondition = findViewById(R.id.tv_applied_condition)
        rgNetworkCondition = findViewById(R.id.rg_network_condition)
        btnRdOn = findViewById(R.id.btn_rd_on)
        btnRdOff = findViewById(R.id.btn_rd_off)
        tvStatus = findViewById(R.id.tv_status)

        // Initialize display
        tvAppliedCondition.text = getString(R.string.current_applied_condition, getString(R.string.normal_bypass))

        btnApplyCondition.setOnClickListener {
            applyNetworkCondition()
        }

        btnRdOn.setOnClickListener {
            if (!isRandomDisconnectActive) {
                isRandomDisconnectActive = true
                startRandomDisconnectCycle()
                Toast.makeText(this, "Random Disconnect: ON", Toast.LENGTH_SHORT).show()
            }
        }

        btnRdOff.setOnClickListener {
            if (isRandomDisconnectActive) {
                isRandomDisconnectActive = false
                stopRandomDisconnectCycle()
                Toast.makeText(this, "Random Disconnect: OFF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyNetworkCondition() {
        val checkedId = rgNetworkCondition.checkedRadioButtonId
        val conditionName = when (checkedId) {
            R.id.rb_normal -> {
                ChaosVpnService.baseDropAll = false
                ChaosVpnService.baseDelayMs = 0
                ChaosVpnService.baseBandwidthBps = 0
                getString(R.string.normal_bypass)
            }
            R.id.rb_loss_100 -> {
                ChaosVpnService.baseDropAll = true
                ChaosVpnService.baseDelayMs = 0
                ChaosVpnService.baseBandwidthBps = 0
                getString(R.string.loss_100)
            }
            R.id.rb_poor -> {
                // Relay traffic (don't drop), but throttle it: ~16 KB/s + 3s added latency
                ChaosVpnService.baseDropAll = false
                ChaosVpnService.baseDelayMs = 3000
                ChaosVpnService.baseBandwidthBps = 16_000
                getString(R.string.poor)
            }
            R.id.rb_very_slow -> {
                // Relay traffic, but throttle it harder than "poor": ~4 KB/s (~32kbps) + 5s added latency
                ChaosVpnService.baseDropAll = false
                ChaosVpnService.baseDelayMs = 5000
                ChaosVpnService.baseBandwidthBps = 4_000
                getString(R.string.very_slow)
            }
            else -> "Unknown"
        }

        tvAppliedCondition.text = getString(R.string.current_applied_condition, conditionName)
        updateVpnState()
        
        if (checkedId != R.id.rb_normal) {
            Toast.makeText(this, "Warning: Internet blocked for timeout testing.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Internet Restored.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRandomDisconnectCycle() {
        isConnectedCycle = true
        runCycle()
    }

    private fun stopRandomDisconnectCycle() {
        handler.removeCallbacksAndMessages(null)
        ChaosVpnService.randomBlock = false
        tvStatus.text = getString(R.string.status_idle)
        updateVpnState()
    }

    private fun runCycle() {
        if (!isRandomDisconnectActive) return

        if (isConnectedCycle) {
            tvStatus.text = getString(R.string.status_connected)
            ChaosVpnService.randomBlock = false
            updateVpnState()
            
            handler.postDelayed({
                isConnectedCycle = false
                runCycle()
            }, 10000)
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            ChaosVpnService.randomBlock = true
            updateVpnState()
            
            handler.postDelayed({
                isConnectedCycle = true
                runCycle()
            }, 3000)
        }
    }

    private fun updateVpnState() {
        if (ChaosVpnService.baseDropAll || ChaosVpnService.baseDelayMs > 0 || ChaosVpnService.randomBlock) {
            prepareAndStartVpn()
        } else {
            stopVpnService()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, ChaosVpnService::class.java).apply {
            action = ChaosVpnService.ACTION_CONNECT
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, ChaosVpnService::class.java).apply {
            action = ChaosVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRandomDisconnectCycle()
    }
}