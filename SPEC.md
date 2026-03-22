# IPTV Player - Specification Document

## 1. Project Overview

**Project Name:** StreamVision IPTV Player  
**Type:** Android Application (Kotlin)  
**Core Functionality:** A modern IPTV player that parses M3U playlists and streams video content using Media3 (ExoPlayer) with a sleek, dark-themed UI.

---

## 2. Technology Stack & Choices

### Framework & Language
- **Language:** Kotlin 1.9.x
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Compile SDK:** 34

### Key Libraries/Dependencies
- **Media3 (ExoPlayer):** 1.2.1 - Core media playback engine
- **Media3 ExoPlayer HLS/DASH:** For adaptive streaming
- **Media3 UI:** For player controls
- **Coroutines:** 1.7.3 - Asynchronous programming
- **Lifecycle Components:** 2.7.0 - ViewModel, LiveData
- **Material Design 3:** 1.11.0 - UI components
- **Hilt:** 2.50 - Dependency injection
- **Coil:** 2.5.0 - Image loading for channel logos
- **Navigation Component:** 2.7.6 - Fragment navigation
- **Room:** 2.6.1 - Local database for favorites/history

### State Management
- MVVM with ViewModels and StateFlow
- Repository pattern for data layer

### Architecture Pattern
- Clean Architecture (Presentation → Domain → Data layers)
- Single Activity with multiple Fragments

---

## 3. Feature List

### Core Features
1. **M3U Playlist Parser**
   - Parse standard M3U/M3U8 playlist files
   - Extract channel name, URL, logo, group, and tvg-info attributes
   - Support both local file and URL-based playlists

2. **Channel List Display**
   - Display all channels in a scrollable list
   - Show channel name, logo, and group
   - Group channels by category (folders)
   - Search/filter channels by name

3. **Media Playback**
   - Stream video using Media3 ExoPlayer
   - Support HLS, DASH, and direct stream URLs
   - Full-screen playback with controls
   - Picture-in-Picture support
   - Background audio playback

4. **Favorites Management**
   - Mark channels as favorites
   - Quick access to favorite channels
   - Persist favorites locally

5. **Playlist Management**
   - Add playlists from URL
   - Add playlists from local file
   - Switch between multiple playlists
   - Edit/remove playlists

6. **Recent History**
   - Track recently watched channels
   - Quick resume to last watched

---

## 4. UI/UX Design Direction

### Overall Visual Style
- **Dark Theme Primary** - Deep dark backgrounds with accent colors
- **Material Design 3** - Modern, clean components
- **Glassmorphism Elements** - Subtle blur effects on overlays

### Color Scheme
- **Primary Background:** #0D1117 (Deep dark)
- **Surface:** #161B22 (Card surfaces)
- **Primary Accent:** #7C3AED (Purple)
- **Secondary Accent:** #22D3EE (Cyan)
- **Text Primary:** #F0F6FC
- **Text Secondary:** #8B949E

### Layout Approach
- **Single Activity + Fragments** architecture
- **Bottom Navigation** with 3 main sections:
  1. Channels - Main channel list
  2. Favorites - Favorite channels
  3. Settings - App settings and playlist management
- **Full-screen Player** overlay with gesture controls

### Key UI Components
- Channel list with card-based items
- Pull-to-refresh for playlist updates
- Floating search bar
- Mini-player at bottom when navigating away
- Smooth animations and transitions
