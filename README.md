# Audiobookshelf TV

A basic native Android TV / Google TV client for [Audiobookshelf](https://www.audiobookshelf.org/).
Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, OkHttp.

## v1 scope
- Sign in with server URL, username and password (token stored on device)
- Continue Listening shelf and a book grid per library (D-pad navigation)
- Book detail with Play / Resume
- Player with ±30s skip, remote media keys, multi-file books as one timeline
- Progress synced to the server through playback sessions (every ~15s, on pause, on exit)

## Not yet (ideas for later)
Podcasts, series/author browsing, search, sleep timer, playback speed, background playback
service / MediaSession, offline downloads, HTTPS-only option, pagination beyond 500 books.

## Install on the TV
1. Push to GitHub; download the `audiobookshelf-tv-debug` artifact from the **Build APK** workflow run.
2. On the TV: Settings > Device Preferences > About > tap *Build* 7 times, then enable
   *Developer options > USB/ADB debugging* (network debugging).
3. `adb connect <tv-ip>:5555 && adb install app-debug.apk`
   (or copy the APK to a USB stick / use a "Send files to TV" app and open it with a file manager).
