package com.example.netchaos

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class ChaosVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val stopVpn = AtomicBoolean(false)
    private var vpnThread: Thread? = null

    companion object {
        const val ACTION_CONNECT = "com.example.netchaos.START"
        const val ACTION_DISCONNECT = "com.example.netchaos.STOP"
        
        var isRunning = false
        
        // Baseline settings from Network Condition
        var baseDropAll = false
        var baseDelayMs: Long = 0
        
        // Temporary override from Random Disconnect
        var randomBlock = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startVpn()
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true
        stopVpn.set(false)

        vpnThread = Thread({
            try {
                val builder = Builder()
                builder.setSession("NetChaos")
                builder.addAddress("10.0.0.2", 32)
                builder.addRoute("0.0.0.0", 0)
                
                vpnInterface = builder.establish()
                
                val input = FileInputStream(vpnInterface?.fileDescriptor)
                val buffer = ByteArray(32768)
                
                while (!stopVpn.get()) {
                    val read = input.read(buffer)
                    if (read > 0) {
                        // Logic: If randomBlock is on OR baseDropAll is on -> Drop
                        if (randomBlock || baseDropAll) {
                            continue
                        }
                        
                        // If not dropping, apply delay if any
                        if (baseDelayMs > 0) {
                            Thread.sleep(baseDelayMs)
                        }
                    }
                    // Small sleep to prevent CPU hogging if no data
                    if (read <= 0) {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChaosVpnService", "Error in VPN thread", e)
            } finally {
                isRunning = false
                vpnInterface?.close()
                vpnInterface = null
            }
        }, "ChaosVpnThread")
        
        vpnThread?.start()
    }

    private fun stopVpn() {
        stopVpn.set(true)
        isRunning = false
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}