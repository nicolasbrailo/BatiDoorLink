package com.nicobrailo.batidoorlink.rtsp

import java.security.MessageDigest
import java.util.Base64

/** A `WWW-Authenticate` challenge, Digest (RFC 2617) or Basic. */
data class AuthChallenge(
    val scheme: String,
    val params: Map<String, String>,
) {
    val realm get() = params["realm"] ?: ""

    companion object {
        private val PARAM = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")

        fun parse(header: String): AuthChallenge? {
            val trimmed = header.trim()
            val scheme = trimmed.substringBefore(' ')
            if (!scheme.equals("Digest", true) && !scheme.equals("Basic", true)) return null
            val params = PARAM.findAll(trimmed.substringAfter(' ', ""))
                .associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
            return AuthChallenge(scheme.lowercase().replaceFirstChar { it.uppercase() }, params)
        }
    }
}

object Auth {
    /**
     * The `Authorization` header answering [challenge] for one request. Only
     * MD5 and qop "auth" (or none, which is what Reolink asks for) are handled.
     */
    fun authorization(
        challenge: AuthChallenge,
        user: String,
        password: String,
        method: String,
        uri: String,
        nonceCount: Int = 1,
        cnonce: String = "0a4f113b",
    ): String {
        if (challenge.scheme == "Basic") {
            return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        }
        val nonce = challenge.params["nonce"] ?: ""
        val ha1 = md5("$user:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")
        val qop = challenge.params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val sb = StringBuilder("Digest username=\"$user\", realm=\"${challenge.realm}\", " +
            "nonce=\"$nonce\", uri=\"$uri\"")
        if (qop != null) {
            val nc = "%08x".format(nonceCount)
            sb.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            sb.append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        challenge.params["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        challenge.params["algorithm"]?.let { sb.append(", algorithm=$it") }
        return sb.toString()
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
