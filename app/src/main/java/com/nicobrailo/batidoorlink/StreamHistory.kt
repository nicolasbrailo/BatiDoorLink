package com.nicobrailo.batidoorlink

import com.nicobrailo.batidoorlink.rtsp.RtspUrl

/**
 * The streams that played, most recent first, for the config panel's
 * dropdown. Each is the full URL, credentials included, since that is what
 * connecting again needs; [label] is what is shown, without them. Kept as
 * one line per URL, which an rtsp:// URL can't break.
 */
object StreamHistory {
    const val MAX = 10

    /** [url] moved (or added) to the top, without duplicates, at most [max] long. */
    fun add(history: List<String>, url: String, max: Int = MAX): List<String> =
        (listOf(url) + history.filter { it != url }).take(max)

    fun decode(stored: String?): List<String> =
        stored?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun encode(history: List<String>): String = history.joinToString("\n")

    /** "10.10.30.11/h264Preview_01_sub": the URL without its scheme and credentials. */
    fun label(url: String): String = try {
        RtspUrl.parse(url).withoutCredentials.removePrefix("rtsp://")
    } catch (_: Exception) {
        url
    }
}
