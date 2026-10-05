package com.nicobrailo.batidoorlink.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    @Test
    fun parsesReolinksChallenge() {
        val c = AuthChallenge.parse("Digest realm=\"BC Streaming Media\", nonce=\"cc28ca4fb50f9a5f5285bcd54ea34d87\"")!!
        assertEquals("Digest", c.scheme)
        assertEquals("BC Streaming Media", c.realm)
        assertEquals("cc28ca4fb50f9a5f5285bcd54ea34d87", c.params["nonce"])
    }

    @Test
    fun answersWithoutQopAsReolinkAsks() {
        // Checked against the response the camera accepted from beep.py.
        val c = AuthChallenge.parse("Digest realm=\"BC Streaming Media\", nonce=\"cc28ca4fb50f9a5f5285bcd54ea34d87\"")!!
        val h = Auth.authorization(c, "admin", "", "DESCRIBE", "rtsp://10.10.30.11:554/h264Preview_01_sub")
        assertTrue(h, h.contains("response=\"34a98549c772f8b9b9aa7a1d74dfbe03\""))
        assertTrue(h, h.contains("uri=\"rtsp://10.10.30.11:554/h264Preview_01_sub\""))
    }

    @Test
    fun answersTheRfc2617Example() {
        val c = AuthChallenge.parse("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
            "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")!!
        val h = Auth.authorization(c, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b")
        assertTrue(h, h.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(h, h.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(h, h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun answersBasic() {
        val c = AuthChallenge.parse("Basic realm=\"cam\"")!!
        assertEquals("Basic YWRtaW46", Auth.authorization(c, "admin", "", "DESCRIBE", "rtsp://x/"))
    }

    @Test
    fun ignoresSchemesItCantAnswer() {
        assertNull(AuthChallenge.parse("Bearer realm=\"x\""))
    }
}
