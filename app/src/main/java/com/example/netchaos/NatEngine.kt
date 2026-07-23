package com.example.netchaos

import android.net.VpnService
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NatEngine"
private const val PROTO_TCP = 6
private const val PROTO_UDP = 17

private const val TCP_FIN = 0x01
private const val TCP_SYN = 0x02
private const val TCP_RST = 0x04
private const val TCP_PSH = 0x08
private const val TCP_ACK = 0x10

private const val MAX_SEGMENT = 1400

private data class Tuple(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

private class TcpSession(
    val tuple: Tuple,
    val socket: Socket,
    var clientSeq: Long,
    var serverSeq: Long
) {
    @Volatile var established = false
    @Volatile var clientClosed = false
    @Volatile var serverClosed = false
    val closed = AtomicBoolean(false)
    val upstreamExecutor = Executors.newSingleThreadExecutor()
}

private class UdpSession(val tuple: Tuple, val socket: DatagramSocket) {
    @Volatile var lastActivity = System.currentTimeMillis()
}

/**
 * Parses IPv4 packets read from the VPN's TUN device and relays TCP/UDP flows
 * to their real destinations through protected sockets (NAT), applying a
 * shared latency + bandwidth budget on the way back into the tun.
 */
class NatEngine(
    private val vpnService: VpnService,
    private val tunOutput: FileOutputStream,
    private val speedProvider: () -> Long,
    private val latencyProvider: () -> Long
) {
    private val tcpSessions = ConcurrentHashMap<Tuple, TcpSession>()
    private val udpSessions = ConcurrentHashMap<Tuple, UdpSession>()
    private val tokenBucket = TokenBucket(0)
    private val writeLock = Any()
    private val running = AtomicBoolean(true)

    private val ioExecutor = Executors.newCachedThreadPool()
    private val scheduler = Executors.newScheduledThreadPool(1)

    init {
        scheduler.scheduleWithFixedDelay({ pruneIdleUdpSessions() }, 30, 30, TimeUnit.SECONDS)
    }

    fun handlePacket(buffer: ByteArray, length: Int) {
        if (length < 20) return
        val version = (buffer[0].toInt() shr 4) and 0xF
        if (version != 4) return
        val ihl = (buffer[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl) return
        val protocol = buffer[9].toInt() and 0xFF
        val srcIp = readInt(buffer, 12)
        val dstIp = readInt(buffer, 16)

        when (protocol) {
            PROTO_TCP -> handleTcp(buffer, ihl, length, srcIp, dstIp)
            PROTO_UDP -> handleUdp(buffer, ihl, length, srcIp, dstIp)
            else -> { /* unsupported protocol for chaos-testing purposes, ignore */ }
        }
    }

    fun shutdown() {
        if (!running.compareAndSet(true, false)) return
        scheduler.shutdownNow()
        ioExecutor.shutdownNow()
        tcpSessions.values.toList().forEach { closeTcpSession(it, sendRst = false) }
        tcpSessions.clear()
        udpSessions.values.toList().forEach { s -> try { s.socket.close() } catch (e: IOException) { } }
        udpSessions.clear()
    }

    // ---------------------------------------------------------------- TCP

    private fun handleTcp(buffer: ByteArray, ipHeaderLen: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        val tcpOffset = ipHeaderLen
        if (totalLen < tcpOffset + 20) return

        val srcPort = readShort(buffer, tcpOffset)
        val dstPort = readShort(buffer, tcpOffset + 2)
        val seq = readIntAsLong(buffer, tcpOffset + 4)
        val dataOffset = ((buffer[tcpOffset + 12].toInt() shr 4) and 0xF) * 4
        val flags = buffer[tcpOffset + 13].toInt() and 0xFF
        val payloadOffset = tcpOffset + dataOffset
        val payloadLen = totalLen - payloadOffset
        if (payloadLen < 0) return
        val tuple = Tuple(srcIp, srcPort, dstIp, dstPort)

        val isSyn = flags and TCP_SYN != 0
        val isAck = flags and TCP_ACK != 0
        val isFin = flags and TCP_FIN != 0
        val isRst = flags and TCP_RST != 0

        if (isRst) {
            tcpSessions.remove(tuple)?.let { closeTcpSession(it, sendRst = false) }
            return
        }

        if (isSyn && !isAck) {
            if (!tcpSessions.containsKey(tuple)) {
                openTcpSession(tuple, seq)
            }
            return
        }

        val session = tcpSessions[tuple] ?: return

        if (!session.established) {
            if (isAck) {
                session.established = true
                startTcpReader(session)
            }
            return
        }

        if (payloadLen > 0 && seq == session.clientSeq) {
            val payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)
            session.clientSeq = (session.clientSeq + payloadLen) and 0xFFFFFFFFL
            session.upstreamExecutor.submit {
                try {
                    applyLatency()
                    throttle(payload.size)
                    session.socket.getOutputStream().write(payload)
                } catch (e: IOException) {
                    closeTcpSession(session, sendRst = true)
                } catch (e: InterruptedException) {
                    // engine shutting down
                }
            }
            sendTcpControl(session, TCP_ACK)
        } else if (payloadLen > 0) {
            // out-of-order or retransmitted data; sequence wraparound isn't handled,
            // acceptable for the short-lived local connections this app relays
            sendTcpControl(session, TCP_ACK)
        }

        if (isFin) {
            session.clientSeq = (session.clientSeq + 1) and 0xFFFFFFFFL
            sendTcpControl(session, TCP_ACK)
            session.clientClosed = true
            try { session.socket.shutdownOutput() } catch (e: IOException) { }
            maybeCloseSession(session)
        }
    }

    private fun openTcpSession(tuple: Tuple, clientIsn: Long) {
        ioExecutor.submit {
            try {
                val socket = Socket()
                vpnService.protect(socket)
                socket.connect(InetSocketAddress(intToInetAddress(tuple.dstIp), tuple.dstPort), 10_000)
                socket.tcpNoDelay = true

                val serverIsn = (Math.random() * 0xFFFFFFFFL).toLong() and 0xFFFFFFFFL
                val session = TcpSession(
                    tuple = tuple,
                    socket = socket,
                    clientSeq = (clientIsn + 1) and 0xFFFFFFFFL,
                    serverSeq = serverIsn
                )
                tcpSessions[tuple] = session
                sendTcpPacket(tuple, seq = serverIsn, ack = session.clientSeq, flags = TCP_SYN or TCP_ACK, payload = null)
                session.serverSeq = (serverIsn + 1) and 0xFFFFFFFFL
            } catch (e: IOException) {
                sendTcpPacket(tuple, seq = 0, ack = (clientIsn + 1) and 0xFFFFFFFFL, flags = TCP_RST or TCP_ACK, payload = null)
            }
        }
    }

    private fun startTcpReader(session: TcpSession) {
        Thread({
            val buf = ByteArray(4096)
            try {
                val input = session.socket.getInputStream()
                while (running.get()) {
                    val n = input.read(buf)
                    if (n == -1) break
                    var offset = 0
                    while (offset < n) {
                        val chunkLen = minOf(MAX_SEGMENT, n - offset)
                        val chunk = buf.copyOfRange(offset, offset + chunkLen)
                        applyLatency()
                        throttle(chunk.size)
                        sendTcpControl(session, TCP_PSH or TCP_ACK, chunk)
                        offset += chunkLen
                    }
                }
            } catch (e: IOException) {
                // fall through to close/FIN handling below
            } catch (e: InterruptedException) {
                // engine shutting down
            }

            if (!session.serverClosed) {
                session.serverClosed = true
                sendTcpControl(session, TCP_FIN or TCP_ACK)
                session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            }
            maybeCloseSession(session)
        }, "TcpReader-${session.tuple}").start()
    }

    private fun maybeCloseSession(session: TcpSession) {
        if (session.clientClosed && session.serverClosed) {
            closeTcpSession(session, sendRst = false)
        }
    }

    private fun closeTcpSession(session: TcpSession, sendRst: Boolean) {
        if (!session.closed.compareAndSet(false, true)) return
        tcpSessions.remove(session.tuple)
        if (sendRst) {
            sendTcpControl(session, TCP_RST or TCP_ACK)
        }
        session.upstreamExecutor.shutdownNow()
        try { session.socket.close() } catch (e: IOException) { }
    }

    private fun sendTcpControl(session: TcpSession, flags: Int, payload: ByteArray? = null) {
        sendTcpPacket(session.tuple, session.serverSeq, session.clientSeq, flags, payload)
        if (payload != null) {
            session.serverSeq = (session.serverSeq + payload.size) and 0xFFFFFFFFL
        }
    }

    private fun sendTcpPacket(tuple: Tuple, seq: Long, ack: Long, flags: Int, payload: ByteArray?) {
        val packet = Packets.buildTcpPacket(
            srcIp = tuple.dstIp, srcPort = tuple.dstPort,
            dstIp = tuple.srcIp, dstPort = tuple.srcPort,
            seq = seq, ack = ack, flags = flags, payload = payload
        )
        writeToTun(packet)
    }

    // ---------------------------------------------------------------- UDP

    private fun handleUdp(buffer: ByteArray, ipHeaderLen: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        val udpOffset = ipHeaderLen
        if (totalLen < udpOffset + 8) return
        val srcPort = readShort(buffer, udpOffset)
        val dstPort = readShort(buffer, udpOffset + 2)
        val payloadOffset = udpOffset + 8
        val payloadLen = totalLen - payloadOffset
        if (payloadLen < 0) return
        val tuple = Tuple(srcIp, srcPort, dstIp, dstPort)

        val session = udpSessions.getOrPut(tuple) {
            val socket = DatagramSocket()
            vpnService.protect(socket)
            val s = UdpSession(tuple, socket)
            startUdpReader(s)
            s
        }
        session.lastActivity = System.currentTimeMillis()
        val payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLen)

        ioExecutor.submit {
            try {
                applyLatency()
                throttle(payload.size)
                session.socket.send(DatagramPacket(payload, payload.size, intToInetAddress(dstIp), dstPort))
            } catch (e: IOException) {
                udpSessions.remove(tuple)
                try { session.socket.close() } catch (e2: IOException) { }
            } catch (e: InterruptedException) {
                // engine shutting down
            }
        }
    }

    private fun startUdpReader(session: UdpSession) {
        Thread({
            val buf = ByteArray(4096)
            try {
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    session.socket.receive(packet)
                    session.lastActivity = System.currentTimeMillis()
                    applyLatency()
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    throttle(data.size)
                    val ipPacket = Packets.buildUdpPacket(
                        srcIp = session.tuple.dstIp, srcPort = session.tuple.dstPort,
                        dstIp = session.tuple.srcIp, dstPort = session.tuple.srcPort,
                        payload = data
                    )
                    writeToTun(ipPacket)
                }
            } catch (e: IOException) {
                // socket closed or destination unreachable; drop the session
            } catch (e: InterruptedException) {
                // engine shutting down
            } finally {
                udpSessions.remove(session.tuple)
                try { session.socket.close() } catch (e: IOException) { }
            }
        }, "UdpReader-${session.tuple}").start()
    }

    private fun pruneIdleUdpSessions() {
        val now = System.currentTimeMillis()
        udpSessions.entries.removeIf { (_, s) ->
            val idle = now - s.lastActivity > 60_000
            if (idle) try { s.socket.close() } catch (e: IOException) { }
            idle
        }
    }

    // ------------------------------------------------------------ shared

    private fun applyLatency() {
        val delay = latencyProvider()
        if (delay > 0) Thread.sleep(delay)
    }

    private fun throttle(byteCount: Int) {
        tokenBucket.bytesPerSecond = speedProvider()
        tokenBucket.consume(byteCount)
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOutput.write(packet)
            } catch (e: IOException) {
                Log.e(TAG, "tun write failed", e)
            }
        }
    }
}

// ----------------------------------------------------------- packet I/O

private fun readInt(buf: ByteArray, offset: Int): Int {
    return ((buf[offset].toInt() and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl 8) or
        (buf[offset + 3].toInt() and 0xFF)
}

private fun readShort(buf: ByteArray, offset: Int): Int {
    return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
}

private fun readIntAsLong(buf: ByteArray, offset: Int): Long {
    return readInt(buf, offset).toLong() and 0xFFFFFFFFL
}

private fun intToInetAddress(ip: Int): InetAddress {
    val bytes = byteArrayOf(
        ((ip shr 24) and 0xFF).toByte(),
        ((ip shr 16) and 0xFF).toByte(),
        ((ip shr 8) and 0xFF).toByte(),
        (ip and 0xFF).toByte()
    )
    return InetAddress.getByAddress(bytes)
}

private object Packets {

    fun buildTcpPacket(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray?
    ): ByteArray {
        val payloadLen = payload?.size ?: 0
        val tcpHeaderLen = 20
        val totalLen = 20 + tcpHeaderLen + payloadLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        buf.put((4 shl 4 or 5).toByte())
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64)
        buf.put(PROTO_TCP.toByte())
        val ipChecksumOffset = buf.position()
        buf.putShort(0)
        buf.putInt(srcIp)
        buf.putInt(dstIp)

        // TCP header
        val tcpStart = buf.position()
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put((5 shl 4).toByte())
        buf.put(flags.toByte())
        buf.putShort(65535.toShort())
        val tcpChecksumOffset = buf.position()
        buf.putShort(0)
        buf.putShort(0)
        if (payload != null) buf.put(payload)

        val bytes = buf.array()

        val ipChecksum = checksum(bytes, 0, 20)
        bytes[ipChecksumOffset] = (ipChecksum shr 8).toByte()
        bytes[ipChecksumOffset + 1] = (ipChecksum and 0xFF).toByte()

        val tcpSegmentLen = tcpHeaderLen + payloadLen
        val pseudo = ByteBuffer.allocate(12 + tcpSegmentLen).order(ByteOrder.BIG_ENDIAN)
        pseudo.putInt(srcIp)
        pseudo.putInt(dstIp)
        pseudo.put(0)
        pseudo.put(PROTO_TCP.toByte())
        pseudo.putShort(tcpSegmentLen.toShort())
        pseudo.put(bytes, tcpStart, tcpSegmentLen)
        val pseudoBytes = pseudo.array()
        val tcpChecksum = checksum(pseudoBytes, 0, pseudoBytes.size)
        bytes[tcpChecksumOffset] = (tcpChecksum shr 8).toByte()
        bytes[tcpChecksumOffset + 1] = (tcpChecksum and 0xFF).toByte()

        return bytes
    }

    fun buildUdpPacket(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpHeaderLen = 8
        val totalLen = 20 + udpHeaderLen + payload.size
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        buf.put((4 shl 4 or 5).toByte())
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64)
        buf.put(PROTO_UDP.toByte())
        val ipChecksumOffset = buf.position()
        buf.putShort(0)
        buf.putInt(srcIp)
        buf.putInt(dstIp)

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((udpHeaderLen + payload.size).toShort())
        buf.putShort(0) // checksum disabled; valid for IPv4 per RFC 768
        buf.put(payload)

        val bytes = buf.array()
        val ipChecksum = checksum(bytes, 0, 20)
        bytes[ipChecksumOffset] = (ipChecksum shr 8).toByte()
        bytes[ipChecksumOffset + 1] = (ipChecksum and 0xFF).toByte()
        return bytes
    }

    private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
