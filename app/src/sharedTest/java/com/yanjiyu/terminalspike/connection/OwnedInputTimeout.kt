package com.yanjiyu.terminalspike.connection

import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/** A test fixture transfers ownership of its input for one bounded blocking read operation. */
internal suspend fun <T> readOwnedInputWithTimeout(
    input: InputStream,
    timeoutMillis: Long,
    read: (InputStream) -> T,
): T = coroutineScope {
    input.use { stream ->
        // Keep ownership outside the timed await: close must run before the scope
        // joins a blocked reader. Carry failures as values until back in the owner's
        // use block, so coroutine stack recovery cannot drop a suppressed close failure.
        val reader = async(Dispatchers.IO) { runCatching { read(stream) } }
        try {
            withTimeout(timeoutMillis) { reader.await() }.getOrThrow()
        } finally {
            reader.cancel()
        }
    }
}
