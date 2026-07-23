package com.example.netchaos

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ChaosVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val stopVpn = AtomicBoolean(false)
    private var vpnThread: Thread? = null
    private var natEngine: NatEngine? = null

    companion object {
        const val ACTION_CONNECT = "com.example.netchaos.START"
        const val ACTION_DISCONNECT = "com.example.netchaos.STOP"

        var isRunning = false

        // Baseline settings from Network Condition
        var baseDropAll = false
        var baseDelayMs: Long = 0
        var baseBandwidthBps: Long = 0 // <= 0 means unlimited

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
                val fd = vpnInterface ?: return@Thread

                val input = FileInputStream(fd.fileDescriptor)
                val output = FileOutputStream(fd.fileDescriptor)
                val engine = NatEngine(
                    vpnService = this,
                    tunOutput = output,
                    speedProvider = { baseBandwidthBps },
                    latencyProvider = { baseDelayMs }
                )
                natEngine = engine

                val buffer = ByteArray(32768)

                while (!stopVpn.get()) {
                    val read = input.read(buffer)
                    if (read > 0) {
                        // Logic: If randomBlock is on OR baseDropAll is on -> Drop
                        if (randomBlock || baseDropAll) {
                            continue
                        }
                        // Otherwise relay the packet (real forwarding, with
                        // latency/bandwidth applied inside the NAT engine)
                        engine.handlePacket(buffer, read)
                    } else {
                        // Small sleep to prevent CPU hogging if no data
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChaosVpnService", "Error in VPN thread", e)
            } finally {
                natEngine?.shutdown()
                natEngine = null
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