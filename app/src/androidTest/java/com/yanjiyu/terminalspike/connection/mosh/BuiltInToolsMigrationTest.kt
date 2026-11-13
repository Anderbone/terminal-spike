package com.yanjiyu.terminalspike.connection.mosh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuiltInToolsMigrationTest {
    @Test
    fun retiredRemovalRecordCannotHideBundledMosh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "plugin-installations")
        val existed = directory.exists()
        val record = File(directory, "transport.mosh")
        val saved = record.takeIf { it.exists() }?.readBytes()
        try {
            check(directory.isDirectory || directory.mkdirs())
            record.writeText("REMOVED\n")
            assertTrue(AndroidMoshExtensionPlatform(context).discover() is MoshDiscoveryDecision.Trusted)
        } finally {
            if (saved == null) record.delete() else record.writeBytes(saved)
            if (!existed) directory.delete()
        }
    }

}
