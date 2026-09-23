# Team Shuffler

Split players into random, balanced teams in one tap. Works on phones and desktop browsers, with no account and no server: saved lists stay on your device (`localStorage`).

## Features
* 4–16 participants, 2–4 teams, sizes kept as even as possible.
* Save name lists and load them again later.
* Installable as an app (PWA) and works offline after the first visit.
* Android app (APK) that tells you when a new version is out.

## Install as an app
* **Android / Chrome:** open the link, then menu → *Install app* (or *Add to Home screen*).
* **iPhone / Safari:** open the link, tap *Share* → *Add to Home Screen*.

When changing `index.html` or the icons, bump `VERSION` in `sw.js` so installed copies pick up the update.

## Android app (APK)
Every change merged into `main` builds a new APK with GitHub Actions and publishes it as a release.
Always the newest version: https://github.com/kosmet-crypto/Team-Shuffler/releases/latest/download/team-shuffler.apk

1. Open the link on your Android phone and download `team-shuffler.apk`.
2. Open the file. Android will ask to allow installs from your browser or file manager; allow it once.
3. Install. Newer APKs install over the old one and keep your saved lists.

The app checks for a newer release at most twice a day and offers to download it. Updates are not silent: you tap **Download**, then open the file to install.

The APK bundles `index.html`, so it works offline from the first launch. Its saved lists are stored inside the app, separately from the browser version.
The Android project lives in `android/` (a small WebView wrapper). To build locally: `cd android && ./gradlew assembleRelease`.
