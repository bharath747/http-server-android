# HTTP File Server for Android

Turn your Android phone into a small HTTP file server. Choose a folder with Android's system folder picker, start the server, then open the displayed URL from another device on the same Wi-Fi network or your phone hotspot.

## Features
- Android 8.0+ (API 26+) and Storage Access Framework; no all-files permission.
- Foreground service keeps the server running while you switch apps.
- Browser file manager with folder navigation, inline file preview, and direct downloads.
- Files are streamed from Android storage; nothing is uploaded to a cloud service.
- GitHub Actions builds a debug APK on every push and pull request.

## Build
Requires JDK 17, Android SDK platform 35, and Gradle 8.9.

```bash
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Use
1. Install the APK from the GitHub Actions artifact.
2. Connect the phone and client device to the same Wi-Fi, or enable the phone's hotspot.
3. Open the app and tap **Choose shared folder**.
4. Tap **START SERVER** and open the shown `http://PHONE_IP:8080` URL on the other device.
5. Browse folders, preview supported file types, or tap Download.

## Security
This first version is read-only and has no login or HTTPS. Anyone able to reach the phone on the local network and guess the URL can read the selected folder. Share only a folder you are comfortable exposing, use a trusted private Wi-Fi/hotspot, and stop the server when finished. Do not forward port 8080 to the internet.

## Scope
This is a lightweight first release inspired by browser-based Android file servers. Upload, delete, authentication, QR codes, ZIP downloads, and resumable/range requests are not included yet.
