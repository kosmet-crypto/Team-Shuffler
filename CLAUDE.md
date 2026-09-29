# Team Shuffler: notes for Claude

Team Shuffler splits players into random teams. One web page (`index.html`, vanilla JS, saved lists
and language in `localStorage`) is shipped three ways: GitHub Pages (web), PWA (`manifest.json`, `sw.js`),
and an Android app (`android/`, a WebView wrapper, package `app.teamshuffler`) whose APK GitHub Actions
builds and publishes as a Release (`team-shuffler.apk`) on every push to `main`.

The owner talks in Serbian (Cyrillic); answer in Serbian. Code, comments and commit messages are in English.

## Privacy
- Commit as `Claude <noreply@anthropic.com>`
  (`git -c user.name=Claude -c user.email=noreply@anthropic.com commit ...`), never with a name or email
  taken from git config, the session or anywhere else. Do not write the owner's name or email into files.

## Workflow
- Work on a branch, open a PR, let the `build` check (Android APK) run, then the owner merges.
  Never force-push `main`.
- Before pushing, test the page in headless Chromium (Playwright is preinstalled): serve the repo
  (`npx http-server -p 8765 -s -c-1`), fill names, draw, and check for page errors. For Android-only
  code paths, stub `window.ShufflerAndroid` with `addInitScript`.
- The Android SDK is not reachable from the dev container; the PR's CI build is the Gradle build.

## App features to keep working
- **Languages:** English, Serbian (Cyrillic) and Norwegian bokmål. All UI text lives in `STRINGS`
  (`en`, `sr`, `nb`) and goes through `t(key, vars)`; static markup uses `data-i18n` /
  `data-i18n-aria`. Every new string needs all three languages. The page calls
  `ShufflerAndroid.setLanguage()` so the native texts in `MainActivity` (EN/SR/NB arrays) match.
- **Draw:** `buildTeams()` honours the hidden rules (tap the header icon 5 times). After a draw,
  tapping two players in different teams swaps them; the shuffle animation is skipped with
  reduced motion.
- **Updates:** "Check for updates" button at the bottom (web: compares `index.html` with the SW cache;
  Android: `ShufflerAndroid.checkForUpdate()`).

## Rules that keep updates working
- **Page changes (`index.html`):** bump `VERSION` in `sw.js` so PWA caches refresh.
- **Silent page updates on Android (`Ota.java`):** the app downloads `index.html` from the newest
  commit on `main`. If the page starts calling a new `ShufflerAndroid` bridge method, raise
  `<meta name="app-native">` in `index.html` and `Ota.NATIVE_API` together; older apps then keep
  their page until the APK is updated.
- **Version numbers:** `versionCode` = Actions `run_number`, release tag `v1.0.<run_number>`. The in-app
  check compares the number after the last dot with the installed `versionCode`; keep them in sync.
- **APK offers:** CI fingerprints `android`, `icons` and `manifest.json` into `BuildConfig.NATIVE_HASH`
  and the release notes (`native: <hash>`). The app only offers an APK when that fingerprint
  changes (`SelfUpdate.java` installs it in-app).
- **Signing:** `android/app/shuffler.keystore` must never change, or installed apps cannot update.

## Standard setup for the owner's apps
When the owner adds a new app repo and asks for "the same as Point / Team Shuffler", set up:
own app icon (web PNGs incl. maskable + Android adaptive and monochrome vectors), PWA manifest and
service worker, Android WebView wrapper with its own package and keystore, CI that builds the APK and
publishes a release, in-app update check with a manual "Check for updates" button, silent page updates,
and a README with install and update instructions.

## Keeping sessions cheap
- Batch small items into one PR, with one CI build and one merge.
- Verify in the browser with numbers (DOM values, counts, page errors); screenshot only when the layout changes.
- Keep replies short: what was done and what the owner should try.
