package com.streamvision.iptv.data.model

import org.junit.Assert.*
import org.junit.Test

class M3UParserTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun m3u(vararg entries: String): String =
        "#EXTM3U\n" + entries.joinToString("\n")

    private fun entry(
        name: String = "Test Channel",
        url: String = "http://example.com/stream.m3u8",
        logo: String? = null,
        group: String? = null,
        extra: String = ""
    ): String {
        val logoAttr  = if (logo  != null) " tvg-logo=\"$logo\""   else ""
        val groupAttr = if (group != null) " group-title=\"$group\"" else ""
        return "#EXTINF:-1$logoAttr$groupAttr$extra,$name\n$url"
    }

    // ------------------------------------------------------------------
    // isValidM3U
    // ------------------------------------------------------------------

    @Test fun `isValidM3U returns true for EXTM3U header`() {
        assertTrue(M3UParser.isValidM3U("#EXTM3U\n#EXTINF:-1,Ch\nhttp://x.com/s"))
    }

    @Test fun `isValidM3U returns true without EXTM3U when EXTINF present`() {
        assertTrue(M3UParser.isValidM3U("#EXTINF:-1,Ch\nhttp://x.com/s"))
    }

    @Test fun `isValidM3U returns false for random text`() {
        assertFalse(M3UParser.isValidM3U("hello world"))
    }

    // ------------------------------------------------------------------
    // Basic parsing
    // ------------------------------------------------------------------

    @Test fun `parse returns empty list for empty content`() {
        assertTrue(M3UParser.parse("", playlistId = 1).isEmpty())
    }

    @Test fun `parse extracts channel name from EXTINF`() {
        val content = m3u(entry(name = "BBC One"))
        val channels = M3UParser.parse(content, playlistId = 1)
        assertEquals(1, channels.size)
        assertEquals("BBC One", channels[0].name)
    }

    @Test fun `parse sets playlistId on every channel`() {
        val content = m3u(
            entry(name = "Ch1"),
            entry(name = "Ch2")
        )
        val channels = M3UParser.parse(content, playlistId = 42)
        assertTrue(channels.all { it.playlistId == 42L })
    }

    @Test fun `parse extracts logo URL`() {
        val content = m3u(entry(logo = "https://example.com/logo.png"))
        val channels = M3UParser.parse(content, playlistId = 1)
        assertEquals("https://example.com/logo.png", channels[0].logo)
    }

    @Test fun `parse extracts group-title`() {
        val content = m3u(entry(group = "Sports"))
        val channels = M3UParser.parse(content, playlistId = 1)
        assertEquals("Sports", channels[0].group)
    }

    @Test fun `parse defaults group to Others when missing`() {
        val content = m3u("#EXTINF:-1,No Group\nhttp://x.com/s")
        val channels = M3UParser.parse(content, playlistId = 1)
        assertEquals("Others", channels[0].group)
    }

    @Test fun `parse extracts URL correctly`() {
        val url = "http://stream.example.com/live/ch1.m3u8"
        val content = m3u(entry(url = url))
        assertEquals(url, M3UParser.parse(content, 1)[0].url)
    }

    // ------------------------------------------------------------------
    // EXTGRP
    // ------------------------------------------------------------------

    @Test fun `parse respects EXTGRP group override`() {
        val content = "#EXTM3U\n#EXTINF:-1,Ch\n#EXTGRP:Movies\nhttp://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("Movies", channel.group)
    }

    // ------------------------------------------------------------------
    // EXTHTTP
    // ------------------------------------------------------------------

    @Test fun `parse extracts cookie from EXTHTTP JSON`() {
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,CookieCh\n" +
            "#EXTHTTP:{\"cookie\":\"session=abc123\"}\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("session=abc123", channel.cookie)
    }

    @Test fun `parse extracts user-agent from EXTHTTP JSON`() {
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,UACh\n" +
            "#EXTHTTP:{\"user-agent\":\"Mozilla/5.0\"}\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("Mozilla/5.0", channel.userAgent)
    }

    // ------------------------------------------------------------------
    // EXTVLCOPT
    // ------------------------------------------------------------------

    @Test fun `parse extracts cookie from EXTVLCOPT`() {
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,VlcCh\n" +
            "#EXTVLCOPT:http-cookie=vlc_cookie=xyz\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("vlc_cookie=xyz", channel.cookie)
    }

    @Test fun `parse extracts user-agent from EXTVLCOPT`() {
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,VlcUA\n" +
            "#EXTVLCOPT:http-user-agent=TestAgent/1.0\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("TestAgent/1.0", channel.userAgent)
    }

    // ------------------------------------------------------------------
    // Pipe-separated headers in URL
    // ------------------------------------------------------------------

    @Test fun `parse extracts cookie from pipe-separated URL`() {
        val content = "#EXTM3U\n#EXTINF:-1,PipeCh\nhttp://x.com/s|Cookie=pipe_cookie"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("http://x.com/s", channel.url)
        assertEquals("pipe_cookie", channel.cookie)
    }

    @Test fun `parse extracts multiple headers from pipe-separated URL with ampersand`() {
        val content = "#EXTM3U\n#EXTINF:-1,PipeCh\n" +
            "http://x.com/s|User-Agent=MyApp&Referer=http://ref.com"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("http://x.com/s", channel.url)
        assertEquals("MyApp", channel.userAgent)
        assertEquals("http://ref.com", channel.referer)
    }

    // ------------------------------------------------------------------
    // DRM — KODIPROP
    // ------------------------------------------------------------------

    @Test fun `parse extracts Widevine license URL from KODIPROP`() {
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,DrmCh\n" +
            "#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha\n" +
            "#KODIPROP:inputstream.adaptive.license_key=https://license.example.com/widevine\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("https://license.example.com/widevine", channel.drmLicenseUrl)
    }

    @Test fun `parse extracts ClearKey keyid and key from KODIPROP`() {
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,ClearKeyCh\n" +
            "#KODIPROP:inputstream.adaptive.license_key=aabbccdd:11223344\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        assertEquals("aabbccdd:11223344", channel.drmKey)
    }

    @Test fun `parse drmKey is not null-prefixed when keyId is missing`() {
        // Regression: was producing "null:key" before fix
        val content = "#EXTM3U\n" +
            "#EXTINF:-1,ClearKeyCh key=\"deadbeef\"\n" +
            "http://x.com/s"
        val channel = M3UParser.parse(content, 1)[0]
        // If key parsed: should start with ":" not "null:"
        channel.drmKey?.let { assertFalse("drmKey must not start with 'null:'", it.startsWith("null:")) }
    }

    // ------------------------------------------------------------------
    // Multiple channels
    // ------------------------------------------------------------------

    @Test fun `parse returns correct count for multiple channels`() {
        val content = m3u(
            entry(name = "Ch1"),
            entry(name = "Ch2"),
            entry(name = "Ch3")
        )
        assertEquals(3, M3UParser.parse(content, 1).size)
    }

    @Test fun `parse resets state between channels`() {
        val content = m3u(
            entry(name = "First", group = "Group A"),
            entry(name = "Second")  // no group — should default to "Others"
        )
        val channels = M3UParser.parse(content, 1)
        assertEquals("Group A", channels[0].group)
        assertEquals("Others", channels[1].group)
    }

    @Test fun `parse skips blank lines and comment-only lines`() {
        val content = "#EXTM3U\n\n# A comment\n\n#EXTINF:-1,OnlyCh\nhttp://x.com/s\n\n"
        assertEquals(1, M3UParser.parse(content, 1).size)
    }
}
