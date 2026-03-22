package com.streamvision.iptv.data.model

import com.streamvision.iptv.domain.model.Channel

/**
 * M3U/M3U8 Playlist Parser
 * Parses standard M3U playlists with extended information
 */
object M3UParser {

    /**
     * Parse M3U content and return list of channels
     */
    fun parse(content: String, playlistId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // Check for EXTINF line (channel metadata)
            if (line.startsWith("#EXTINF:")) {
                val metadata = parseExtInfLine(line)
                
                // Get channel name - priority: explicit name > tvg-name > display-name
                var name = metadata["name"] ?: ""
                if (name.isBlank()) {
                    name = metadata["tvg_name"] ?: ""
                }
                if (name.isBlank()) {
                    name = metadata["display_name"] ?: ""
                }
                
                // Get DRM information
                val drmLicenseUrl = metadata["license_url"] ?: metadata["drm_license_url"] ?: ""
                val drmKeys = metadata["keys"] ?: metadata["drm_keys"] ?: ""
                
                // Next line should be the URL
                if (i + 1 < lines.size) {
                    val urlLine = lines[i + 1].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                        val channel = Channel(
                            name = name.ifBlank { "Unknown Channel" },
                            url = urlLine,
                            logo = metadata["tvg_logo"] ?: metadata["logo"],
                            group = metadata["group_title"] ?: metadata["group"],
                            playlistId = playlistId,
                            drmLicenseUrl = drmLicenseUrl.ifBlank { null },
                            drmKey = drmKeys.ifBlank { null }
                        )
                        channels.add(channel)
                        i++ // Skip the URL line
                    }
                }
            } else if (line.startsWith("#EXTGRP:")) {
                // Extended group information - skip
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                // Direct URL without EXTINF (simple M3U)
                val channel = Channel(
                    name = extractNameFromUrl(line),
                    url = line,
                    playlistId = playlistId
                )
                channels.add(channel)
            }
            i++
        }
        
        return channels
    }

    /**
     * Parse #EXTINF line and extract all metadata
     * Handles formats like:
     * #EXTINF:-1 tvg-name="Name" tvg-logo="..." group-title="Group",Channel Name
     * #EXTINF:-1,[tvg-name="Name" tvg-logo="..."]Channel Name
     * #EXTINF:0 group-title="Group",Channel Name
     */
    private fun parseExtInfLine(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Remove #EXTINF: prefix
        val extInfContent = line.removePrefix("#EXTINF:")
        
        // Find the first comma that separates duration from the rest
        val firstComma = extInfContent.indexOf(',')
        
        if (firstComma < 0) {
            // No comma - just attributes, no channel name
            val attrs = parseAllAttributes(extInfContent.trim())
            result.putAll(attrs)
            return result
        }
        
        // Get the part before the first comma (duration + attributes)
        val beforeComma = extInfContent.substring(0, firstComma).trim()
        // Get the part after the first comma (channel name or more attributes)
        var afterComma = extInfContent.substring(firstComma + 1).trim()
        
        result["duration"] = beforeComma
        
        // Parse attributes from before comma
        val beforeAttrs = parseAllAttributes(beforeComma)
        result.putAll(beforeAttrs)
        
        // Now handle the after comma part
        // It could be: just channel name, or [attributes]channel name, or more attributes
        
        // First check if there's a bracket with attributes
        val bracketMatch = Regex("""^\[([^\]]+)\](.*)$""").find(afterComma)
        
        if (bracketMatch != null) {
            // Found bracket with attributes
            val bracketContent = bracketMatch.groupValues[1]
            val afterBracket = bracketMatch.groupValues[2].trim()
            
            // Parse attributes from bracket
            val bracketAttrs = parseAllAttributes(bracketContent)
            result.putAll(bracketAttrs)
            
            // Channel name is after the bracket
            if (afterBracket.isNotBlank()) {
                result["name"] = afterBracket
            }
        } else if (afterComma.startsWith("\"")) {
            // The after comma part starts with quote - it's a quoted channel name
            // Find the closing quote
            val endQuote = afterComma.indexOf("\"", 1)
            if (endQuote > 0) {
                result["name"] = afterComma.substring(1, endQuote)
            } else {
                result["name"] = afterComma
            }
        } else {
            // No bracket - check if afterComma has more attributes or is just the name
            // Check if it looks like attributes (has =")
            if (afterComma.contains("=")) {
                // More attributes in after comma
                val afterAttrs = parseAllAttributes(afterComma)
                // Check if there's a channel name in the after comma
                // It's usually after all the attributes
                val nameMatch = Regex("""(.+?)\s*=\s*["'].+$""").find(afterComma)
                if (nameMatch != null) {
                    // Get everything before the first attribute-like pattern
                    val nameCandidate = afterComma.substringBefore("=").trim()
                    if (nameCandidate.isNotBlank() && !nameCandidate.contains("[")) {
                        result["name"] = nameCandidate
                    }
                }
                result.putAll(afterAttrs)
            } else {
                // Just channel name
                result["name"] = afterComma
            }
        }
        
        return result
    }

    /**
     * Parse all attributes from a string - handles both key="value" and key='value'
     */
    private fun parseAllAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Match key="value" or key='value'
        val regex = Regex("""([a-zA-Z0-9_-]+)=["']([^"']*)["']""")
        
        regex.findAll(attrString).forEach { match ->
            val key = match.groupValues[1].lowercase().replace("-", "_")
            val value = match.groupValues[2]
            
            when (key) {
                "tvg_name" -> result["tvg_name"] = value
                "tvg_logo" -> result["tvg_logo"] = value
                "logo" -> result["logo"] = value
                "group_title" -> result["group_title"] = value
                "group" -> result["group"] = value
                "tvg_id" -> result["tvg_id"] = value
                "display_name" -> result["display_name"] = value
                "license_url" -> result["license_url"] = value
                "drm_license_url" -> result["drm_license_url"] = value
                "keys" -> result["keys"] = value
                "drm_keys" -> result["drm_keys"] = value
            }
        }
        
        return result
    }

    /**
     * Extract a readable name from URL
     */
    private fun extractNameFromUrl(url: String): String {
        return try {
            val path = url.substringAfter("://").substringBefore("?")
            val fileName = path.substringAfterLast("/").substringBeforeLast(".")
            if (fileName.isNotEmpty()) {
                fileName.replace("_", " ").replace("-", " ")
                    .split(" ").joinToString(" ") { word ->
                        word.replaceFirstChar { it.uppercase() }
                    }
            } else {
                "Unknown Channel"
            }
        } catch (e: Exception) {
            "Unknown Channel"
        }
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
