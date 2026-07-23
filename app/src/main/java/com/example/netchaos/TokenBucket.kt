package com.example.netchaos

/**
 * Simple shared-pipe rate limiter. bytesPerSecond <= 0 means unlimited.
 * consume() blocks the calling thread until enough tokens are available,
 * which naturally serializes throughput across all callers sharing one bucket.
 */
class TokenBucket(@Volatile var bytesPerSecond: Long) {

    private var tokens: Double = (bytesPerSecond / 5.0).coerceAtLeast(0.0)
    private var lastRefillNanos: Long = System.nanoTime()

    @Synchronized
    fun consume(byteCount: Int) {
        val limit = bytesPerSecond
        if (limit <= 0 || byteCount <= 0) return

        var remaining = byteCount.toDouble()
        while (remaining > 0) {
            refill(limit)
            val take = minOf(tokens, remaining)
            tokens -= take
            remaining -= take
            if (remaining > 0) {
                val waitMs = ((remaining / limit) * 1000).toLong().coerceIn(1L, 200L)
                Thread.sleep(waitMs)
            }
        }
    }

    private fun refill(limit: Long) {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            tokens = minOf(limit.toDouble(), tokens + elapsedSeconds * limit)
            lastRefillNanos = now
        }
    }
}
