# Note3 LAN Backup

A tiny, dependency-free Android application designed for Samsung Galaxy Note 3 `SM-N9008S` running Android 5.0 (API 21).

It starts a read-only HTTP server on the phone's local Wi-Fi network. A computer on the same Wi-Fi can open the displayed URL and download:

- Recommended backup ZIP: photos, pictures, downloads, documents, media, Tencent/WeChat-style shared folders, contacts, SMS, call logs, installed-app list, and device information
- Full readable shared-storage ZIP, including detected external SD cards
- Individual files and folder ZIP archives through a browser-based file explorer
- Contacts as VCF
- SMS and call logs as CSV

## Security model

- Read-only server, with no upload, delete, or rename endpoints
- Random six-digit access token in every URL
- Server exists only while the app is open and running
- Intended only for a trusted home LAN

## Android limitations

The app can read shared storage and system data explicitly covered by the permissions granted during installation. It cannot back up another app's private sandbox, account passwords, DRM-protected data, or protected system partitions without root.

## Build

The GitHub Actions workflow builds a debug-signed APK compatible with Android 5.0 and commits it to the repository root as `Note3-LAN-Backup.apk`.
