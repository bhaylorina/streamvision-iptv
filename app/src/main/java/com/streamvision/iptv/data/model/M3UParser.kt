package com.streamvision.iptv.data.model

import com.streamvision.iptv.domain.model.Channel
import java.util.regex.Pattern

/**
 * M3U/M3U8 Playlist Parser
 * Based on Bluise IPTV Parser
 */
object M3UParser {

    /**
     * Parse M3U content and return list of channels
     */
    fun parse(content: String, playlistId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()
        
        // Variables for current channel
        var currentTitle: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentLicenseKey: String? = null
        var currentKeyId: String? = null
        var currentKey: String? = null
        var currentUa: String? = null
        var currentCookie: String? = null
        var currentReferer: String? = null
        var currentDrmScheme: String? = null
        
        for (line in lines) {
            val trim = line.trim()
            if (trim.isEmpty()) continue
            
            // Parse EXTINF line
            if (trim.startsWith("#EXTINF:")) {
                // Get channel name - everything after last comma
                currentTitle = trim.substringAfterLast(",").trim()
                
                // Parse all attributes using regex
                val regex = Pattern.compile("""([a-zA-Z0-9_.-]+)=?("[^"]*"|[^\s"]+)""")
                val matcher = regex.matcher(trim)
                
                while (matcher.find()) {
                    var keyName = matcher.group(1)?.lowercase() ?: ""
                    var value = matcher.group(2) ?: ""
                    
                    // Remove quotes from value
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length - 1)
                    }
                    value = value.trim()
                    
                    when (keyName) {
                        "tvg-logo", "logo" -> currentLogo = value
                        "group-title" -> currentGroup = value
                        "keyid", "kid" -> currentKeyId = value
                        "key", "license_key" -> currentKey = value
                        "tvg-name" -> if (currentTitle.isNullOrBlank()) currentTitle = value
                    }
                }
            }
            // Parse EXTGRP line
            else if (trim.startsWith("#EXTGRP:")) {
                currentGroup = trim.substringAfter(":").trim()
            }
            // Parse EXTHTTP (headers/cookies)
            else if (trim.startsWith("#EXTHTTP:")) {
                try {
                    val jsonStr = trim.removePrefix("#EXTHTTP:").trim()
                    val json = org.json.JSONObject(jsonStr)
                    if (json.has("cookie")) currentCookie = json.optString("cookie")
                    if (json.has("user-agent")) currentUa = json.optString("user-agent")
                    if (json.has("referer")) currentReferer = json.optString("referer")
                } catch (e: Exception) { }
            }
            // Parse KODIPROP (DRM info)
            else if (trim.startsWith("#KODIPROP:")) {
                val prop = trim.removePrefix("#KODIPROP:").trim()
                
                if (prop.startsWith("inputstream.adaptive.license_type=")) {
                    currentDrmScheme = prop.substringAfter("=").trim()
                }
                else if (prop.startsWith("inputstream.adaptive.license_key=")) {
                    val keyValue = prop.removePrefix("inputstream.adaptive.license_key=").trim()
                    
                    // Check for keyid= and key= format
                    if (keyValue.contains("keyid=") && keyValue.contains("key=")) {
                        val kidMatcher = Pattern.compile("keyid=([a-fA-F0-9]+)").matcher(keyValue)
                        if (kidMatcher.find()) currentKeyId = kidMatcher.group(1)
                        val keyMatcher = Pattern.compile("key=([a-fA-F0-9]+)").matcher(keyValue)
                        if (keyMatcher.find()) currentKey = keyMatcher.group(1)
                    }
                    // Check for keyid:key format
                    else if (keyValue.contains(":") && !keyValue.startsWith("http")) {
                        val parts = keyValue.split(":")
                        if (parts.size >= 2) {
                            currentKeyId = parts[0].trim()
                            currentKey = parts[1].trim()
                        }
                    }
                    // Check for HTTP URL format
                    else if (keyValue.startsWith("http")) {
                        currentLicenseKey = keyValue
                    }
                    else {
                        // Default: try parsing as keyid:key
                        currentKey = keyValue
                    }
                }
            }
            // Parse EXTVLCOPT
            else if (trim.startsWith("#EXTVLCOPT:")) {
                if (trim.contains("http-user-agent=")) {
                    currentUa = trim.substringAfter("=").trim()
                }
                if (trim.contains("http-referrer=")) {
                    currentReferer = trim.substringAfter("=").trim()
                }
                if (trim.contains("http-cookie=")) {
                    currentCookie = trim.substringAfter("=").trim()
                }
            }
            // URL line (not starting with #)
            else if (!trim.startsWith("#")) {
                var finalUrl = trim
                
                // Handle URL with pipe-separated headers
                if (trim.contains("|")) {
                    val parts = trim.split("|")
                    finalUrl = parts[0].trim()
                    
                    if (parts.size > 1) {
                        val params = parts[1].split("&")
                        for (p in params) {
                            val keyVal = p.split("=", limit = 2)
                            if (keyVal.size == 2) {
                                val headerKey = keyVal[0].trim()
                                val headerValue = keyVal[1].trim()
                                
                                when {
                                    headerKey.equals("User-Agent", true) -> currentUa = headerValue
                                    headerKey.equals("Referer", true) -> currentReferer = headerValue
                                    headerKey.equals("Cookie", true) -> currentCookie = headerValue
                                }
                            }
                        }
                    }
                }
                
                val channel = Channel(
                    name = currentTitle ?: "Unknown Channel",
                    url = finalUrl,
                    logo = currentLogo,
                    group = if (currentGroup.isNullOrEmpty()) "Others" else currentGroup,
                    playlistId = playlistId,
                    drmLicenseUrl = currentLicenseKey,
                    drmKey = if (currentKey != null) "$currentKeyId:$currentKey" else null,
                    userAgent = currentUa,
                    cookie = currentCookie,
                    referer = currentReferer
                )
                channels.add(channel)
                
                // Reset all variables for next channel
                currentTitle = null
                currentLogo = null
                currentGroup = null
                currentLicenseKey = null
                currentKeyId = null
                currentKey = null
                currentUa = null
                currentCookie = null
                currentReferer = null
                currentDrmScheme = null
            }
        }
        
        return channels
    }

    /**
     * Check if content appears to be a valid M3U playlist
     */
    fun isValidM3U(content: String): Boolean {
        return content.contains("#EXTM3U") || content.lines().any { 
            it.trim().startsWith("#EXTINF:") || 
            (!it.trim().startsWith("#") && it.trim().isNotEmpty() && 
             (it.trim().startsWith("http://") || it.trim().startsWith("https://")))
        }
    }
}
