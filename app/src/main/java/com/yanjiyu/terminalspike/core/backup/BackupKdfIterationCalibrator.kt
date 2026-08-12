package com.yanjiyu.terminalspike.core.backup

import kotlin.math.roundToLong

/**
 * One-shot PBKDF2 calibration primitive targeting roughly 750 ms on the current runtime.
 *
 * Persisting the returned value is intentionally left to the later backup repository/UX slice.
 */
class BackupKdfIterationCalibrator internal constructor(
    private val benchmark: Pbkdf2Benchmark,
) {
    constructor() : this(JcaPbkdf2Benchmark)

    fun calibrate(): Int {
        val measuredNanos = benchmark.measureNanos(BackupEnvelopeFormat.MIN_KDF_ITERATIONS)
        if (measuredNanos <= 0) return BackupEnvelopeFormat.MAX_KDF_ITERATIONS
        val scaled = (
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS.toDouble() *
                TARGET_NANOS.toDouble() / measuredNanos.toDouble()
            ).roundToLong()
        return scaled.coerceIn(
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS.toLong(),
            BackupEnvelopeFormat.MAX_KDF_ITERATIONS.toLong(),
        ).toInt()
    }

    companion object {
        const val TARGET_MILLIS = 750L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val TARGET_NANOS = TARGET_MILLIS * NANOS_PER_MILLISECOND
    }
}

internal fun interface Pbkdf2Benchmark {
    fun measureNanos(iterations: Int): Long
}

private object JcaPbkdf2Benchmark : Pbkdf2Benchmark {
    override fun measureNanos(iterations: Int): Long {
        val passphrase = "terminal-spike-backup-kdf-calibration".toCharArray()
        val salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES) { index -> (index + 1).toByte() }
        var key: ByteArray? = null
        return try {
            val startedAt = System.nanoTime()
            key = JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(passphrase, salt, iterations)
            (System.nanoTime() - startedAt).coerceAtLeast(1L)
        } finally {
            passphrase.fill('\u0000')
            salt.fill(0)
            key?.fill(0)
        }
    }
}
