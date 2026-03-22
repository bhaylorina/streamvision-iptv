# StreamVision IPTV Player

A modern IPTV player for Android built with Media3 (ExoPlayer) featuring M3U playlist parsing, channel favorites, and a sleek dark-themed UI.

## Features

- **M3U Playlist Parser**: Parse standard M3U/M3U8 playlists with extended metadata (channel name, logo, group)
- **Media Playback**: Stream video using Media3 ExoPlayer with support for HLS, DASH, and direct stream URLs
- **Channel Management**: Browse channels by group, search and filter
- **Favorites**: Mark channels as favorites for quick access
- **Multiple Playlists**: Add and manage multiple playlists
- **Dark Theme**: Modern dark-themed UI with Material Design 3
- **Picture-in-Picture**: Support for PiP mode on Android 8+

## Tech Stack

- **Language**: Kotlin 1.9.x
- **Architecture**: Clean Architecture + MVVM
- **Media Player**: Media3 ExoPlayer 1.2.1
- **DI**: Hilt 2.50
- **Database**: Room 2.6.1
- **Navigation**: Navigation Component 2.7.6
- **Image Loading**: Coil 2.5.0

## Project Structure

```
app/src/main/java/com/streamvision/iptv/
├── StreamVisionApp.kt          # Application class
├── data/
│   ├── local/                   # Room database, DAOs, entities
│   ├── model/                   # Data models and M3U parser
│   └── repository/              # Repository implementations
├── domain/
│   ├── model/                   # Domain models (Channel, Playlist)
│   ├── repository/              # Repository interfaces
│   └── usecase/                 # Use cases
├── di/                          # Hilt dependency injection modules
└── presentation/
    ├── adapter/                 # RecyclerView adapters
    ├── ui/                      # Activities and Fragments
    └── viewmodel/               # ViewModels
```

## Prerequisites

- Android Studio Arctic Fox or newer
- JDK 17
- Android SDK 34

## Building

1. Clone the repository
2. Open in Android Studio
3. Let Gradle sync and build

Or from command line:

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/`

## Adding Playlists

Once installed, you can add M3U playlists:

1. Tap the + button on the Channels tab
2. Enter a name for the playlist
3. Enter the M3U URL (e.g., `https://example.com/playlist.m3u`)
4. Tap Add

Example free IPTV playlists can be found online.

## Usage

- **Channels**: Browse all channels, filter by group, search
- **Favorites**: View your favorite channels
- **Settings**: Manage playlists

Tap on any channel to start playback in full-screen mode.

## License

MIT License
