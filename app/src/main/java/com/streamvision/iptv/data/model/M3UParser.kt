package com.streamvision.iptv.data.model

import com.streamvision.iptv.domain.model.Channel

/**
 * M3U/M3U8 Playlist Parser
 * Parses M3U playlists with extended information
 * Supports standard M3U and JioTV/Bee IPS-style playlists
 */
object M3UParser {

    /**
     * Parse M3U content and return list of channels
     */
    fun parse(content: String, playlistId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()
        
        var i = 0
        var pendingDrmLicenseType: String? = null
        var pendingDrmKeys: String? = null
        var pendingHttpHeaders: String? = null
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // Check for KODIPROP (DRM info) - comes before the EXTINF or URL
            if (line.startsWith("#KODIPROP:")) {
                val prop = line.removePrefix("#KODIPROP:")
                when {
                    prop.contains("license_type") -> {
                        pendingDrmLicenseType = prop.substringAfter("=").trim()
                    }
                    prop.contains("license_key") -> {
                        pendingDrmKeys = prop.substringAfter("=").trim()
                    }
                }
                i++
                continue
            }
            
            // Check for EXTHTTP (HTTP headers/cookies)
            if (line.startsWith("#EXTHTTP:")) {
                pendingHttpHeaders = line.removePrefix("#EXTHTTP:").trim()
                i++
                continue
            }
            
            // Check for EXTINF line (channel metadata)
            if (line.startsWith("#EXTINF:")) {
                val metadata = parseExtInfLine(line)
                
                // Get channel name - priority: explicit name > tvg-name
                var name = metadata["name"] ?: ""
                if (name.isBlank()) {
                    name = metadata["tvg_name"] ?: ""
                }
                
                // Next line should be the URL
                if (i + 1 < lines.size) {
                    val urlLine = lines[i + 1].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                        // Check if DRM is needed (clearkey)
                        val drmLicenseUrl = if (pendingDrmLicenseType == "clearkey") {
                            // For ClearKey, we need to construct the license URL
                            // The keys format is key1:key2 - we'll store it in drmKey
                            pendingDrmKeys
                        } else null
                        
                        val channel = Channel(
                            name = name.ifBlank { "Unknown Channel" },
                            url = urlLine,
                            logo = metadata["tvg_logo"] ?: metadata["logo"],
                            group = metadata["group_title"] ?: metadata["group"],
                            playlistId = playlistId,
                            drmLicenseUrl = drmLicenseUrl,
                            drmKey = if (pendingDrmLicenseType == "clearkey") pendingDrmKeys else null
                        )
                        channels.add(channel)
                        
                        // Reset DRM info after use
                        pendingDrmLicenseType = null
                        pendingDrmKeys = null
                        pendingHttpHeaders = null
                        i++ // Skip the URL line
                    }
                }
            }
            i++
        }
        
        return channels
    }

    /**
     * Parse #EXTINF line
     * Format: #EXTINF:-1[="Dummy"] tvg-id="..." group-title="..." tvg-logo="...",Channel Name
     */
    private fun parseExtInfLine(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Remove #EXTINF: prefix
        val extInfContent = line.removePrefix("#EXTINF:")
        
        // Find the first comma
        val firstComma = extInfContent.indexOf(',')
        
        if (firstComma < 0) {
            // No comma - just attributes
            val attrs = parseAllAttributes(extInfContent.trim())
            result.putAll(attrs)
            return result
        }
        
        // Get the part before comma (duration + attributes)
        val beforeComma = extInfContent.substring(0, firstComma).trim()
        // Get the part after comma (channel name)
        val afterComma = extInfContent.substring(firstComma + 1).trim()
        
        // Parse attributes from before comma
        val attrs = parseAllAttributes(beforeComma)
        result.putAll(attrs)
        
        // Channel name is after the comma
        if (afterComma.isNotBlank()) {
            result["name"] = afterComma
        }
        
        return result
    }

    /**
     * Parse all attributes from a string
     * Handles: key="value" or key='value'
     * Also handles: ="value" (like ="Dummy")
     */
    private fun parseAllAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Match key="value" or key='value' or ="value"
        val regex = Regex("""([a-zA-Z0-9_-]+)?=["']([^"']*)["']""")
        
        regex.findAll(attrString).forEach { match ->
            val key = (match.groupValues[1].ifEmpty { "dummy" }).lowercase().replace("-", "_")
            val value = match.groupValues[2]
            
            when (key) {
                "dummy" -> { /* Skip ="Dummy" */ }
                "tvg_name" -> result["tvg_name"] = value
                "tvg_id" -> result["tvg_id"] = value
                "tvg_logo" -> result["tvg_logo"] = value
                "logo" -> result["logo"] = value
                "group_title" -> result["group_title"] = value
                "group" -> result["group"] = value
                "group_logo" -> result["group_logo"] = value
            }
        }
        
        return result
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
