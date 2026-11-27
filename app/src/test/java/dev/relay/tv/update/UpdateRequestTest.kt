package dev.relay.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRequestTest {
    @Test
    fun noTokenSendsNoAuthorizationHeader() {
        assertNull(UpdateRequest.authHeader(""))
        assertNull(UpdateRequest.authHeader("   "))
    }

    @Test
    fun aTokenIsSentAsBearerWithoutStraySpacesOnlyToGitHubApi() {
        assertEquals("Bearer abc", UpdateRequest.authHeader("abc"))
        assertEquals("Bearer abc", UpdateRequest.authHeader("  abc \n", "api.github.com"))
        assertNull(UpdateRequest.authHeader("abc", "objects.githubusercontent.com"))
        assertNull(UpdateRequest.authHeader("abc", "evil.example"))
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

    @Test
    fun onlyHttpsGitHubHostsAreTrustedForUpdates() {
        assertTrue(UpdateRequest.isTrustedUrl("https://api.github.com/repos/ghoshs89/TV-Relay/releases/latest"))
        assertTrue(UpdateRequest.isTrustedUrl("https://github.com/ghoshs89/TV-Relay/releases/download/v1.0.1/Relay.apk"))
        assertTrue(UpdateRequest.isTrustedUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(UpdateRequest.isTrustedUrl("http://github.com/ghoshs89/TV-Relay/releases/download/v1.0.1/Relay.apk"))
        assertFalse(UpdateRequest.isTrustedUrl("https://evil.example/Relay.apk"))
        assertFalse(UpdateRequest.isTrustedUrl(null))
    }
}
