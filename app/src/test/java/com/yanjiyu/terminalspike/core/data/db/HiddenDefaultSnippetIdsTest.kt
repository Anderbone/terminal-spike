package com.yanjiyu.terminalspike.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HiddenDefaultSnippetIdsTest {
    @Test fun emptyAndDistinctStableIdsRoundTripCanonically() {
        assertEquals("", HiddenDefaultSnippetIds.encode(emptySet()))
        assertEquals(emptySet<Long>(), HiddenDefaultSnippetIds.decode(""))
        assertEquals("-2,-1,0", HiddenDefaultSnippetIds.encode(setOf(0, -1, -2)))
        assertEquals(setOf(-2L, -1L, 0L), HiddenDefaultSnippetIds.decode("0,-1,-2,-1"))
    }

    @Test fun savedSnippetIdsAndMalformedMetadataAreRejected() {
        for (encoded in listOf("1", "0,nope", "-9223372036854775809", ",")) {
            assertThrows(IllegalArgumentException::class.java) { HiddenDefaultSnippetIds.decode(encoded) }
        }
        assertThrows(IllegalArgumentException::class.java) { HiddenDefaultSnippetIds.encode(setOf(1L)) }
    }

    @Test fun metadataSizeAndCardinalityAreBoundedBeforeItCanReachTheUI() {
        assertThrows(IllegalArgumentException::class.java) { HiddenDefaultSnippetIds.decode("0".repeat(256 * 21 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { HiddenDefaultSnippetIds.decode(List(257) { "0" }.joinToString(",")) }
        assertThrows(IllegalArgumentException::class.java) { HiddenDefaultSnippetIds.encode((0L downTo -256L).toSet()) }
    }
}
