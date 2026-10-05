package com.nicobrailo.batidoorlink.rtsp

/** One `m=` section of an SDP description, with what the backchannel needs. */
data class MediaSection(
    val media: String,
    val payloadTypes: List<Int>,
    /** Payload type to encoding name, from `a=rtpmap` ("PCMU" from "PCMU/8000"). */
    val encodings: Map<Int, String>,
    /** Payload type to RTP clock rate, from `a=rtpmap` (8000 from "PCMU/8000"). */
    val clockRates: Map<Int, Int> = emptyMap(),
    /** Payload type to its `a=fmtp` parameters, keys in lower case. */
    val formats: Map<Int, Map<String, String>> = emptyMap(),
    val control: String?,
    /** sendonly, recvonly, sendrecv or inactive, as the server wrote it, or null. */
    val direction: String?,
) {
    /** The first payload type this section offers in [encoding] (case insensitive), if any. */
    fun payloadTypeFor(encoding: String): Int? =
        payloadTypes.firstOrNull { encodings[it].equals(encoding, ignoreCase = true) }
}

/** The backchannel a server offers: where to SETUP it and what to send. */
data class BackchannelTrack(val control: String, val codec: G711)

object Sdp {
    private val DIRECTIONS = setOf("sendonly", "recvonly", "sendrecv", "inactive")

    fun parse(sdp: String): List<MediaSection> {
        val sections = mutableListOf<MediaSection>()
        var media: String? = null
        var payloadTypes = emptyList<Int>()
        var encodings = mutableMapOf<Int, String>()
        var clockRates = mutableMapOf<Int, Int>()
        var formats = mutableMapOf<Int, Map<String, String>>()
        var control: String? = null
        var direction: String? = null

        fun flush() {
            media?.let {
                sections += MediaSection(it, payloadTypes, encodings, clockRates = clockRates, formats = formats,
                    control = control, direction = direction)
            }
        }

        for (raw in sdp.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("m=") -> {
                    flush()
                    // m=audio 0 RTP/AVP 0 8
                    val parts = line.substring(2).split(' ').filter { it.isNotEmpty() }
                    media = parts.getOrNull(0) ?: ""
                    payloadTypes = parts.drop(3).mapNotNull { it.toIntOrNull() }
                    encodings = mutableMapOf()
                    clockRates = mutableMapOf()
                    formats = mutableMapOf()
                    control = null
                    direction = null
                }
                media == null -> Unit // Session-level lines aren't needed.
                line.startsWith("a=rtpmap:") -> {
                    // a=rtpmap:0 PCMU/8000
                    val rest = line.substring("a=rtpmap:".length)
                    val pt = rest.substringBefore(' ').toIntOrNull() ?: continue
                    val encoding = rest.substringAfter(' ').trim()
                    encodings[pt] = encoding.substringBefore('/')
                    encoding.split('/').getOrNull(1)?.toIntOrNull()?.let { clockRates[pt] = it }
                }
                line.startsWith("a=fmtp:") -> {
                    // a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=...
                    val rest = line.substring("a=fmtp:".length)
                    val pt = rest.substringBefore(' ').toIntOrNull() ?: continue
                    formats[pt] = rest.substringAfter(' ').split(';')
                        .map { it.trim() }.filter { '=' in it }
                        .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
                }
                line.startsWith("a=control:") -> control = line.substring("a=control:".length).trim()
                line.startsWith("a=") && line.substring(2) in DIRECTIONS -> direction = line.substring(2)
            }
        }
        flush()
        return sections
    }

    /**
     * The ONVIF backchannel: the audio section the server marks `sendonly`
     * (it describes the stream from the client's side), in a codec we can
     * produce. Null when there isn't one, as when DESCRIBE lacked the
     * `Require` header.
     */
    fun backchannel(sections: List<MediaSection>): BackchannelTrack? {
        for (s in sections) {
            if (s.media != "audio" || s.direction != "sendonly") continue
            val control = s.control ?: continue
            for (pt in s.payloadTypes) {
                // A static payload type needs no rtpmap, but Reolink sends one anyway.
                val codec = s.encodings[pt]?.let { G711.fromEncodingName(it) } ?: G711.fromPayloadType(pt)
                if (codec != null) return BackchannelTrack(control, codec)
            }
        }
        return null
    }

    /**
     * Resolves a control attribute against the base URL (Content-Base, else
     * the URL that was described), as RFC 2326 C.1.1 says: an absolute URL is
     * used as it is, `*` means the base itself, and anything else is relative.
     */
    fun resolveControl(control: String, base: String): String = when {
        control.startsWith("rtsp://", ignoreCase = true) -> control
        control == "*" -> base
        else -> base.trimEnd('/') + "/" + control.trimStart('/')
    }
}
