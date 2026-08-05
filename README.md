# KinderDash

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg?logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Made with Jetpack Compose](https://img.shields.io/badge/Made%20with-Jetpack%20Compose-green.svg?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A personal fork of [**JohnnWi/homelab-project**](https://github.com/JohnnWi/homelab-project),
continued for one specific purpose: **a full-page Android home-screen widget** that aggregates a
self-hosted homelab into a single glanceable page.

Upstream is an excellent multi-service dashboard app, but it has no home-screen widget — and the
widget is the whole reason this fork exists. Everything else here is upstream's work.

---

## ⚠️ Read this first

**This is a personal project, published only because GitHub forks cannot be made private.**

- **Not a product, not a distribution, not seeking users.** It is built for my own phones and shaped
  entirely around what I personally run.
- **No support.** Please don't open issues or pull requests — I won't be triaging them. If something
  here is useful to you, fork it and make it yours; that's what I did.
- **No releases, no App Store, no sideload source.** Nothing is published anywhere.
- **It will break.** It changes whenever I want it to, with no regard for compatibility, migrations,
  or anyone else's setup.
- **The iOS app (`HomelabSwift/`) is inherited and unmaintained here.** I don't use iOS and won't be
  touching it. For a maintained iOS continuation, see
  [unitsung/homelab-project](https://github.com/unitsung/homelab-project), which is actively
  developing the Swift side.

If you want the original app as its author intended it, go
[upstream](https://github.com/JohnnWi/homelab-project) — including its full documentation and the
list of 34 supported integrations, which I haven't duplicated here because it would only drift.

---

## Why this fork exists

The goal is a home-screen surface that answers "is anything wrong?" without unlocking the phone or
opening anything — container health, service uptime, active streams, pending requests, in one
glance.

Two design constraints shape it:

1. **Full-page, not a tile.** It targets a whole home-screen page via a max-size
   [Jetpack Glance](https://developer.android.com/jetpack/androidx/releases/glance) widget.
2. **Foldable-first layout.** The primary device is a book-style foldable, which is roomier and
   *squarer* than a normal phone in both folded and open states. So layouts are designed square-first
   and degrade to tall-narrow, which is the opposite of the usual approach.

---

## Changes from upstream

Per Apache 2.0 §4(b), the notable modifications so far:

| Change | Why |
|---|---|
| Credential columns encrypted with an Android Keystore AES-256-GCM key | Upstream stored API keys, tokens and passwords as plaintext Room columns |
| Cloud-backup and device-transfer rules exclude all domains | `allowBackup="false"` does not block D2D transfer on Android 12+, and DataStore lives in the `file` domain, not `sharedpref` |
| In-app update check moved to `BuildConfig`, empty by default and skipped when unset | It was hardcoded to upstream's repo, so every fork phoned home to its parent and offered a differently-signed APK that could never install |
| Accepted Android Studio's `org.gradle.tooling.parallel` setting | Avoids the tree being dirtied on every sync |

Planned, not yet built: a cross-service aggregation layer and the Glance widget itself.

---

## Building

Android only. Open **`HomelabAndroid/`** in Android Studio — not the repository root, which also
contains the iOS project.

```
cd HomelabAndroid
./gradlew assembleDebug
```

Requires JDK 21. Note that `gradle/gradle-daemon-jvm.properties` (inherited from upstream) pins the
Gradle **daemon** to a JetBrains-vendor JVM, so a non-JBR `JAVA_HOME` will be used only for the
launcher.

---

## Credits and license

All original work is by [**JohnnWi**](https://github.com/JohnnWi) and the contributors to
[homelab-project](https://github.com/JohnnWi/homelab-project), licensed under the
**Apache License 2.0**. This fork is distributed under the same license — see [LICENSE](LICENSE).

Modifications in this repository are marked in *Changes from upstream* above and in the commit
history.

> **Disclaimer:** carried over from upstream and still true — provided as-is, with no guarantees,
> and no responsibility assumed for issues, data loss, or damages arising from its use.
