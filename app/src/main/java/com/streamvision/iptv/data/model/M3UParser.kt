package com.streamvision.iptv.data.model

import android.util.Log
import com.streamvision.iptv.domain.model.Channel
import java.util.regex.Pattern

/**
 * M3U/M3U8 Playlist Parser
 */
object M3UParser {

    private const val TAG = "M3UParser"

    /**
     * Parse M3U content and return list of channels.
     */
    fun parse(content: String, playlistId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()

        var currentTitle: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentLicenseKey: String? = null
        var currentKeyId: String? = null
        var currentKey: String? = null
        var currentUa: String? = null
        var currentCookie: String? = null
        var currentReferer: String? = null

        for (line in lines) {
            val trim = line.trim()
            if (trim.isEmpty()) continue

            when {
                // EXTINF line — channel metadata
                trim.startsWith("#EXTINF:") -> {
                    currentTitle = trim.substringAfterLast(",").trim()

                    val regex = Pattern.compile("""([a-zA-Z0-9_.-]+)=?("[^"]*"|[^\s"]+)""")
                    val matcher = regex.matcher(trim)
                    while (matcher.find()) {
                        val keyName = matcher.group(1)?.lowercase() ?: continue
                        var value = matcher.group(2) ?: continue
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length - 1)
                        }
                        value = value.trim()
                        when (keyName) {
                            "tvg-logo", "logo" -> currentLogo = value
                            "group-title"      -> currentGroup = value
                            "keyid", "kid"     -> currentKeyId = value
                            "key", "license_key" -> currentKey = value
                            "tvg-name" -> if (currentTitle.isNullOrBlank()) currentTitle = value
                        }
                    }
                }

                // EXTGRP — group override
                trim.startsWith("#EXTGRP:") -> {
                    currentGroup = trim.substringAfter(":").trim()
                }

                // EXTHTTP — JSON headers/cookies
                trim.startsWith("#EXTHTTP:") -> {
                    try {
                        val json = org.json.JSONObject(trim.removePrefix("#EXTHTTP:").trim())
                        if (json.has("cookie"))      currentCookie  = json.optString("cookie")
                        if (json.has("user-agent"))  currentUa      = json.optString("user-agent")
                        if (json.has("User-Agent"))  currentUa      = json.optString("User-Agent")
                        if (json.has("referer"))     currentReferer = json.optString("referer")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing EXTHTTP", e)
                    }
                }

                // EXTVLCOPT — VLC-style options
                trim.startsWith("#EXTVLCOPT:") -> {
                    val opt = trim.removePrefix("#EXTVLCOPT:").trim()
                    when {
                        opt.startsWith("http-cookie=")     -> currentCookie  = opt.removePrefix("http-cookie=").trim()
                        opt.startsWith("http-user-agent=") -> currentUa      = opt.removePrefix("http-user-agent=").trim()
                        opt.startsWith("http-referrer=")   -> currentReferer = opt.removePrefix("http-referrer=").trim()
                    }
                }

                // KODIPROP — DRM info
                trim.startsWith("#KODIPROP:") -> {
                    val prop = trim.removePrefix("#KODIPROP:").trim()
                    when {
                        prop.startsWith("inputstream.adaptive.license_type=") ->
                            { /* DRM scheme noted but not yet surfaced to the Channel model */ }

                        prop.startsWith("inputstream.adaptive.license_key=") -> {
                            val keyValue = prop.removePrefix("inputstream.adaptive.license_key=").trim()
                            when {
                                keyValue.contains("keyid=") && keyValue.contains("key=") -> {
                                    val kidMatcher = Pattern.compile("keyid=([a-fA-F0-9]+)").matcher(keyValue)
                                    if (kidMatcher.find()) currentKeyId = kidMatcher.group(1)
                                    val keyMatcher = Pattern.compile("key=([a-fA-F0-9]+)").matcher(keyValue)
                                    if (keyMatcher.find()) currentKey = keyMatcher.group(1)
                                }
                                keyValue.contains(":") && !keyValue.startsWith("http") -> {
                                    val parts = keyValue.split(":")
                                    if (parts.size >= 2) {
                                        currentKeyId = parts[0].trim()
                                        currentKey   = parts[1].trim()
                                    }
                                }
                                keyValue.startsWith("http") -> currentLicenseKey = keyValue
                                else                        -> currentKey = keyValue
                            }
                        }
                    }
                }

                // URL line — pipe-separated headers or plain URL
                !trim.startsWith("#") -> {
                    var finalUrl = trim

                    if (trim.contains("|")) {
                        val parts = trim.split("|")
                        finalUrl = parts[0].trim()
                        // First split: url|Cookie=xxx&User-Agent=yyy  OR  url|Cookie=xxx|User-Agent=yyy
                        if (parts.size > 1) {
                            val headerSource = if (parts[1].contains("&")) {
                                parts[1].split("&")
                            } else {
                                parts.drop(1)
                            }
                            for (hdr in headerSource) {
                                val kv = hdr.split("=", limit = 2)
                                if (kv.size == 2) when {
                                    kv[0].equals("User-Agent", true) -> currentUa      = kv[1].trim()
                                    kv[0].equals("Referer",    true) -> currentReferer = kv[1].trim()
                                    kv[0].equals("Cookie",     true) -> currentCookie  = kv[1].trim()
                                }
                            }
                        }
                    }

                    val channel = Channel(
                        name         = currentTitle ?: "Unknown Channel",
                        url          = finalUrl,
                        logo         = currentLogo,
                        group        = if (currentGroup.isNullOrEmpty()) "Others" else currentGroup,
                        playlistId   = playlistId,
                        drmLicenseUrl = currentLicenseKey,
                        // Fix: avoid "null:key" when keyId is missing
                        drmKey       = if (currentKey != null) "${currentKeyId.orEmpty()}:$currentKey" else null,
                        userAgent    = currentUa,
                        cookie       = currentCookie,
                        referer      = currentReferer
                    )
                    channels.add(channel)

                    // Reset per-channel state
                    currentTitle = null; currentLogo = null; currentGroup = null
                    currentLicenseKey = null; currentKeyId = null; currentKey = null
                    currentUa = null; currentCookie = null; currentReferer = null
                }
            }
        }

        return channels
    }

    /**
     * Returns true if [content] looks like a valid M3U playlist.
     */
    fun isValidM3U(content: String): Boolean {
        return content.contains("#EXTM3U") || content.lines().any {
            val t = it.trim()
            t.startsWith("#EXTINF:") ||
                (!t.startsWith("#") && t.isNotEmpty() &&
                    (t.startsWith("http://") || t.startsWith("https://")))
        }
    }
}
