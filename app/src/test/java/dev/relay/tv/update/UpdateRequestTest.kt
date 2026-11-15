package dev.relay.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRequestTest {
    @Test
    fun noTokenSendsNoAuthorizationHeader() {
        assertNull(UpdateRequest.authHeader(""))
        assertNull(UpdateRequest.authHeader("   "))
    }

    @Test
    fun aTokenIsSentAsBearerWithoutStraySpaces() {
        assertEquals("Bearer abc", UpdateRequest.authHeader("abc"))
        assertEquals("Bearer abc", UpdateRequest.authHeader("  abc \n"))
    }

    @Test
    fun withoutATokenThePlainDownloadAddressIsUsed() {
        assertEquals("https://dl", UpdateRequest.assetUrl("", "https://api", "https://dl"))
        assertEquals("https://api", UpdateRequest.assetUrl("", "https://api", null))
        assertNull(UpdateRequest.assetUrl("", null, null))
    }

    @Test
    fun withATokenTheApiAddressIsUsedBecauseAPrivateRepositoryServesNothingElse() {
        assertEquals("https://api", UpdateRequest.assetUrl("abc", "https://api", "https://dl"))
        assertEquals("https://dl", UpdateRequest.assetUrl("abc", null, "https://dl"))
    }
}
