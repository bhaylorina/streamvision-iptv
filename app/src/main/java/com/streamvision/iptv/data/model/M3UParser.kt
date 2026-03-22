package com.streamvision.iptv.data.model

import com.streamvision.iptv.domain.model.Channel

/**
 * M3U/M3U8 Playlist Parser
 * Parses standard M3U playlists with extended information
 */
object M3UParser {

    /**
     * Parse M3U content and return list of channels
     * @param content The M3U file content as string
     * @param playlistId The ID of the playlist this content belongs to
     * @return List of parsed channels
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
                
                // Get channel name from multiple sources
                var name = metadata["name"] ?: ""
                if (name.isEmpty()) {
                    name = metadata["tvg-name"] ?: ""
                }
                if (name.isEmpty()) {
                    name = metadata["display-name"] ?: ""
                }
                
                // Get DRM information
                val drmLicenseUrl = metadata["drm-license-url"] ?: ""
                val drmKeys = metadata["drm-keys"] ?: ""
                
                // Next line should be the URL
                if (i + 1 < lines.size) {
                    val urlLine = lines[i + 1].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                        val channel = Channel(
                            name = name.ifEmpty { "Unknown Channel" },
                            url = urlLine,
                            logo = metadata["logo"] ?: metadata["tvg-logo"],
                            group = metadata["group"] ?: metadata["group-title"],
                            playlistId = playlistId,
                            drmLicenseUrl = drmLicenseUrl,
                            drmKey = drmKeys
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
     * Parse #EXTINF line and extract metadata
     * Supports multiple formats:
     * #EXTINF:-1 tvg-name="Name" tvg-logo="..." group-title="..."
     * #EXTINF:0 group-title="Group",Channel Name
     * #EXTINF:-1 tvg-name="Name" tvg-logo="..." group-title="...",[DRM license-url="..." keys="..."]
     */
    private fun parseExtInfLine(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Remove #EXTINF: prefix
        val extInfContent = line.removePrefix("#EXTINF:")
        
        // Split by first comma to get duration and rest
        val firstComma = extInfContent.indexOf(',')
        if (firstComma > 0) {
            val duration = extInfContent.substring(0, firstComma).trim()
            result["duration"] = duration
            
            var rest = extInfContent.substring(firstComma + 1).trim()
            
            // Check for DRM info in brackets [DRM ...]
            val drmBracketStart = rest.indexOf("[DRM")
            val drmBracketEnd = rest.indexOf(']')
            
            if (drmBracketStart >= 0 && drmBracketEnd > drmBracketStart) {
                val drmInfo = rest.substring(drmBracketStart + 4, drmBracketEnd).trim()
                val drmAttrs = parseAttributes(drmInfo)
                result["drm-license-url"] = drmAttrs["license-url"] ?: ""
                result["drm-keys"] = drmAttrs["keys"] ?: ""
                rest = rest.substring(0, drmBracketStart).trim()
            }
            
            // Check for tvg-info in brackets [tvg-name="..." tvg-logo="..." group-title="..."]
            val bracketStart = rest.indexOf('[')
            val bracketEnd = rest.indexOf(']')
            
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                val tvgInfo = rest.substring(bracketStart + 1, bracketEnd)
                val attributes = parseAttributes(tvgInfo)
                result.putAll(attributes)
                
                // Channel name is after the brackets
                val afterBracket = rest.substring(bracketEnd + 1).trim()
                if (afterBracket.isNotEmpty()) {
                    result["name"] = afterBracket
                }
            } else {
                // Check for attributes in the extinf line itself (space-separated before comma)
                val beforeComma = extInfContent.substring(0, firstComma).trim()
                val attrs = parseAttributes(beforeComma)
                result.putAll(attrs)
                
                // Channel name is after any attributes
                if (rest.isNotEmpty()) {
                    // Remove any remaining attributes
                    val nameMatch = Regex("""([^,\[]+)$""").find(rest)
                    result["name"] = nameMatch?.groupValues?.get(1)?.trim() ?: rest
                }
            }
        } else {
            // No comma - parse attributes from the whole line
            val attrs = parseAttributes(extInfContent.trim())
            result.putAll(attrs)
            result["name"] = attrs["tvg-name"] ?: extInfContent.trim()
        }
        
        return result
    }

    /**
     * Parse attribute string like tvg-name="Name" tvg-logo="url" group-title="Group" license-url="..." keys="..."
     */
    private fun parseAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Match attributes with quotes
        val regex = """(\w+-?\w*)=["']([^"']*)["']""".toRegex()
        
        regex.findAll(attrString).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            
            when (key) {
                "tvg-name" -> result["tvg-name"] = value
                "tvg-logo" -> result["tvg-logo"] = value
                "logo" -> result["logo"] = value
                "group-title" -> result["group-title"] = value
                "group" -> result["group"] = value
                "tvg-id" -> result["tvg-id"] = value
                "display-name" -> result["display-name"] = value
                "drm-license-url", "license-url" -> result["drm-license-url"] = value
                "drm-keys", "keys" -> result["drm-keys"] = value
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
