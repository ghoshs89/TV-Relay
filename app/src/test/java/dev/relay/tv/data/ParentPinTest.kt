package dev.relay.tv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentPinTest {

    @Test
    fun correctPinVerifies() {
        val stored = ParentPin.hash("2580")
        assertTrue(ParentPin.verify("2580", stored))
    }

    @Test
    fun wrongOrMissingPinFails() {
        val stored = ParentPin.hash("2580")
        assertFalse(ParentPin.verify("2581", stored))
        assertFalse(ParentPin.verify("", stored))
        assertFalse(ParentPin.verify("2580", null))
        assertFalse(ParentPin.verify("2580", ""))
    }

    @Test
    fun pinIsNotStoredInPlainTextAndIsSalted() {
        val a = ParentPin.hash("2580")
        val b = ParentPin.hash("2580")
        assertFalse(a.contains("2580"))
        assertNotEquals(a, b)
    }

    @Test
    fun onlyFourToEightDigitsAreAccepted() {
        assertTrue(ParentPin.isValid("1234"))
        assertTrue(ParentPin.isValid("12345678"))
        assertFalse(ParentPin.isValid("123"))
        assertFalse(ParentPin.isValid("123456789"))
        assertFalse(ParentPin.isValid("12a4"))
    }

    @Test
    fun pbkdf2V2HashesVerifyAlongsideLegacyV1Hashes() {
        val v2 = ParentPin.hashV2("4826", iterations = 2_000)
        assertTrue(v2.startsWith("v2$"))
        assertTrue(ParentPin.verify("4826", v2))
        assertFalse(ParentPin.verify("4827", v2))
        assertFalse(ParentPin.verify("4826", "v2$10$abc$def"))
        assertFalse(ParentPin.verify("4826", "v2$bad"))
    }
}
