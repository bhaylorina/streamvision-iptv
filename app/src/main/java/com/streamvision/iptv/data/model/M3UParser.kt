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
                val name = metadata["name"] ?: ""
                
                // Next line should be the URL
                if (i + 1 < lines.size) {
                    val urlLine = lines[i + 1].trim()
                    if (urlLine.isNotEmpty() && !urlLine.startsWith("#")) {
                        val channel = Channel(
                            name = name,
                            url = urlLine,
                            logo = metadata["logo"],
                            group = metadata["group"],
                            playlistId = playlistId
                        )
                        channels.add(channel)
                        i++ // Skip the URL line
                    }
                }
            } else if (line.startsWith("#EXTGRP:")) {
                // Extended group information
                // This applies to the next channel
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
     * Format: #EXTINF:[duration],[tvg-name="..." tvg-logo="..." group-title="..."],Channel Name
     */
    private fun parseExtInfLine(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // Remove #EXTINF: prefix
        val extInfContent = line.removePrefix("#EXTINF:")
        
        // Split by comma to get duration and rest
        val commaIndex = extInfContent.indexOf(',')
        if (commaIndex > 0) {
            val duration = extInfContent.substring(0, commaIndex).trim()
            result["duration"] = duration
            
            val rest = extInfContent.substring(commaIndex + 1).trim()
            
            // Check for tvg-info in brackets [tvg-name="..." tvg-logo="..." group-title="..."]
            val bracketStart = rest.indexOf('[')
            val bracketEnd = rest.indexOf(']')
            
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                val tvgInfo = rest.substring(bracketStart + 1, bracketEnd)
                val attributes = parseAttributes(tvgInfo)
                result.putAll(attributes)
                
                // Channel name is after the brackets
                result["name"] = rest.substring(bracketEnd + 1).trim()
            } else {
                // No tvg-info, the whole rest is the channel name
                result["name"] = rest
            }
        } else {
            result["name"] = extInfContent.trim()
        }
        
        return result
    }

    /**
     * Parse attribute string like tvg-name="Name" tvg-logo="url" group-title="Group"
     */
    private fun parseAttributes(attrString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = """(\w+)=["']([^"']*)["']""".toRegex()
        
        regex.findAll(attrString).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            
            when (key.lowercase()) {
                "tvg-name" -> result["tvg-name"] = value
                "tvg-logo" -> result["logo"] = value
                "group-title" -> result["group"] = value
                "tvg-id" -> result["tvg-id"] = value
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
