package com.yanjiyu.terminalspike.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupKdfTest {
    @Test
    fun pbkdf2HmacSha256MatchesKnownAsciiAndUtf8VectorsWithoutTrimming() {
        val salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES) { (it + 1).toByte() }
        val asciiPassphrase = "password".toCharArray()
        val unicodePassphrase = "  pässphrase 🔒  ".toCharArray()

        val ascii = JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(
            asciiPassphrase,
            salt,
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )
        val unicode = JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(
            unicodePassphrase,
            salt,
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )

        assertArrayEquals(
            "680956406d064c6ea856a6f6fded2cf1bf64e3dd85c70ad6d364eeb7310e55b2".hexBytes(),
            ascii,
        )
        assertArrayEquals(
            "7e826d3d0e4853ffc94faa9c3a18db50863e81f111c8b6785546f600a306d424".hexBytes(),
            unicode,
        )
        assertFalse(
            unicode.contentEquals(
                "69dee36ecf8160db50be679b582baacd6c449f8f3d1d9f16dace37e2c5675d2a".hexBytes(),
            ),
        )
        assertAllZero(asciiPassphrase)
        assertAllZero(unicodePassphrase)
        ascii.fill(0)
        unicode.fill(0)
        salt.fill(0)
    }

    @Test
    fun invalidIterationPassphraseLengthAndUnicodeAreBoundedAndStillWiped() {
        val salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES)
        val tooLittleWork = "secret".toCharArray()
        assertEquals(
            BackupLimit.KDF_ITERATIONS,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(
                    tooLittleWork,
                    salt,
                    BackupEnvelopeFormat.MIN_KDF_ITERATIONS - 1,
                )
            }.limit,
        )
        assertAllZero(tooLittleWork)

        val tooMuchWork = "secret".toCharArray()
        assertEquals(
            BackupLimit.KDF_ITERATIONS,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(
                    tooMuchWork,
                    salt,
                    BackupEnvelopeFormat.MAX_KDF_ITERATIONS + 1,
                )
            }.limit,
        )
        assertAllZero(tooMuchWork)

        val oversized = CharArray(BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS + 1) { 'x' }
        assertEquals(
            BackupLimit.PASSPHRASE_LENGTH,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(
                    oversized,
                    salt,
                    BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
                )
            }.limit,
        )
        assertAllZero(oversized)

        val invalidUnicode = charArrayOf('\ud800')
        assertEquals(
            BackupMalformedReason.INVALID_UTF8,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                JcaPbkdf2KeyDeriver.deriveAndWipePassphrase(
                    invalidUnicode,
                    salt,
                    BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
                )
            }.reason,
        )
        assertAllZero(invalidUnicode)
    }

    @Test
    fun calibrationScalesToward750MillisecondsAndClampsBothEnds() {
        assertEquals(
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            calibratorForElapsedMillis(2_000).calibrate(),
        )
        assertEquals(
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            calibratorForElapsedMillis(750).calibrate(),
        )
        assertEquals(200_000, calibratorForElapsedMillis(375).calibrate())
        assertEquals(750_000, calibratorForElapsedMillis(100).calibrate())
        assertEquals(
            BackupEnvelopeFormat.MAX_KDF_ITERATIONS,
            calibratorForElapsedMillis(1).calibrate(),
        )
        assertEquals(
            BackupEnvelopeFormat.MAX_KDF_ITERATIONS,
            BackupKdfIterationCalibrator(Pbkdf2Benchmark { 0 }).calibrate(),
        )
    }

    @Test
    fun configuredIterationConstantsAreInternallyConsistent() {
        assertTrue(BackupEnvelopeFormat.MIN_KDF_ITERATIONS > 0)
        assertTrue(
            BackupEnvelopeFormat.DEFAULT_KDF_ITERATIONS in
                BackupEnvelopeFormat.MIN_KDF_ITERATIONS..BackupEnvelopeFormat.MAX_KDF_ITERATIONS,
        )
        assertEquals(750L, BackupKdfIterationCalibrator.TARGET_MILLIS)
        assertEquals(32, BackupEnvelopeFormat.AES_KEY_BYTES)
    }

    private fun calibratorForElapsedMillis(milliseconds: Long) =
        BackupKdfIterationCalibrator(Pbkdf2Benchmark { milliseconds * 1_000_000 })

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun assertAllZero(passphrase: CharArray) {
        assertTrue("Expected mutable passphrase to be wiped", passphrase.all { it == '\u0000' })
    }
}
