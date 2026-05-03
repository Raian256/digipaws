package neth.iecal.curbox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashUtilsTest {

    @Test
    fun sha256_emptyString_matchesKnownDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtils.sha256("")
        )
    }

    @Test
    fun sha256_abc_matchesKnownDigest() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtils.sha256("abc")
        )
    }

    @Test
    fun sha256_helloWorld_matchesKnownDigest() {
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            HashUtils.sha256("hello world")
        )
    }

    @Test
    fun sha256_outputIs64LowercaseHexChars() {
        val digest = HashUtils.sha256("any-input-here")
        assertEquals(64, digest.length)
        assertTrue("digest must be lowercase hex", digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun sha256_isDeterministic() {
        assertEquals(HashUtils.sha256("repeat-me"), HashUtils.sha256("repeat-me"))
    }

    @Test
    fun sha256_distinctInputsProduceDistinctDigests() {
        assertNotEquals(HashUtils.sha256("a"), HashUtils.sha256("b"))
    }

    @Test
    fun sha256_handlesUnicode() {
        // Verifies UTF-8 byte encoding rather than platform-default — ensures the
        // pin/pass hashing stays stable across locales.
        assertEquals(
            "850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e",
            HashUtils.sha256("café")
        )
    }
}
