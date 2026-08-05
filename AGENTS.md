# AGENTS.md — KinderDash

Instructions for AI agents working in this repository.

This is a **personal fork** of the archived [JohnnWi/homelab-project](https://github.com/JohnnWi/homelab-project),
kept for one purpose: adding a **full-page Android home-screen widget**. Read `README.md` first.

Upstream's AGENTS.md described a two-platform release process on macOS. None of it applied here, so
this file replaces it rather than amending it.

---

## What this fork is, and is not

- **Android only.** `HomelabSwift/` is inherited and unmaintained — do not modify it, do not build
  it, do not fix it. If a change would touch iOS, stop and say so instead.
- **Nothing is released.** No signed artefacts, no GitHub releases, no AltStore/SideStore source, no
  store listings. Version bumps are free of cross-platform coupling.
- **No external contributors.** No PR review flow, no issue triage.
- **`origin` is the only remote** (`ChaosieKinder/kinderdash`). `upstream` and `unitsung` exist
  locally as read-only references and must never be pushed to.

## Repository layout

- `HomelabAndroid/` — the Android app. **This is the Gradle project root**, not the repo root.
- `HomelabSwift/` — inherited iOS app. Out of scope.
- `docs/`, `apps.json` — upstream leftovers, inert. Don't build on them.
- `app-version.json` — a template for the opt-in update check. Safe to hand-edit; upstream's rule
  against editing it does not apply, because the workflow that owned it has been removed.

## Branch and commit strategy

- **Branch for anything non-trivial**; don't commit straight to `main`. Names: `feat/…`, `fix/…`,
  `security/…`, `docs/…`, `chore/…`.
- Fast-forward onto `main` when the work is done and verified.
- Conventional commit subjects (`feat:`, `fix:`, `docs:`, `chore:`, `security:`).
- **Explain *why* in the body, not just what.** Several decisions here look like mistakes without
  their reasoning — see *Decisions that look wrong* below.

## Build and verification

Windows-native builds; the tree lives on an NTFS drive and building it through WSL is ~8× slower.

```bash
cd HomelabAndroid
./gradlew assembleDebug              # or :app:compileDebugKotlin for a quick check
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumented — needs a device attached
```

Requires JDK 21. Note `gradle/gradle-daemon-jvm.properties` pins the Gradle **daemon** to a
JetBrains-vendor JVM, so a non-JBR `JAVA_HOME` is used only for the launcher.

**Verification policy:** run a compile check for any Kotlin/resource change. Run unit tests when
touching logic, networking, parsing, storage or ViewModels. Docs-only changes need no build.
Instrumented tests only run with a device attached — if none is, say so rather than claiming they
passed.

CI (`.github/workflows/ci.yml`) is Android-only and checks: no tracked release binaries, no
hardcoded update URLs, compile, unit tests, and that instrumented tests still compile.

## Decisions that look wrong without their reasons

Do not "fix" these without reading why:

- **`setUserAuthenticationRequired(false)`** in `CredentialCipher`. An auth-bound Keystore key
  cannot be used while the device is locked, which would break the widget's background refresh —
  the entire point of the app.
- **Credential encryption lives in the entity↔domain mappers**, not in the schema or a
  TypeConverter. `ServiceInstanceDao` is injected into exactly one class, which makes those mappers
  a real chokepoint. **If you add a credential column, wrap it there too.**
- **`CredentialCipher.encrypt` falls back to plaintext on failure; `decrypt` returns null.** Failing
  closed on save would discard a credential the user just typed. Failing closed on read costs only
  a re-entry.
- **Update-check URLs default to empty** and the check is skipped when unset. This is deliberate so
  that forks of *this* repo don't phone home to us — which is the bug we inherited from upstream.
  Never reintroduce a literal URL; CI fails on it.
- **Glance stays at 1.1.1.** That is the current stable release; 1.2.0 never shipped past `rc01`.
  `SizeMode.Responsive` — the API the widget needs — is already in 1.1.x.

## Widget work (the actual goal)

- Target surfaces are a book-style foldable (**10:16 folded, 4:3 open**) and a standard phone.
  Both foldable states are *squarer* than a normal phone, so **lay out square-first and let the
  tall-narrow phone grid be the degraded case** — the opposite of the usual instinct.
- Build on `SizeMode.Responsive` from the start; retrofitting it is painful.
- Prefer extending the existing `getSummary()` convention on repositories (see `KomodoRepository`,
  `UptimeKumaRepository`, `PlexRepository`) over new bespoke data paths. Widget refreshes must stay
  cheap — never call a heavyweight `getDashboard()` on a refresh cycle.

## Security

This app stores homelab service credentials. Accordingly:

- Never log credential values, and never add them to crash/analytics payloads.
- Never put real hostnames, IP addresses, tokens or topology into this repository — **it is public**
  (GitHub forks cannot be made private). Test fixtures use obviously fake values.
- Both backup rule files exclude every domain in both sections. Don't narrow them.
