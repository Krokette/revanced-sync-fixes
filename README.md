# Sync fixes

[![Build](https://github.com/Krokette/revanced-sync-fixes/actions/workflows/build.yml/badge.svg)](https://github.com/Krokette/revanced-sync-fixes/actions/workflows/build.yml)

Two [ReVanced](https://revanced.app) patches that make **Sync for Reddit** work again after Reddit's API change of
October 2026:

| Patch | Fixes |
|---|---|
| **Fix Reddit galleries** | Galleries no longer open ("Error loading album") |
| **Fix load more comments** | "Error loading more comments" when tapping "View more" |

They are for **Sync for Reddit v23.06.30-13:39**, the free version (`com.laurencedawson.reddit_sync`), patched with
the official ReVanced patches (Spoof client and the rest).

## Install

You add this bundle to ReVanced Manager next to the official one, then patch Sync again.

1. In ReVanced Manager, go to **Patches**, tap the **pencil icon** (bottom right), then **Add patches** →
   **Enter URL**, and paste:

   ```
   https://raw.githubusercontent.com/Krokette/revanced-sync-fixes/main/bundles/stable.json
   ```

   Manager then keeps the bundle up to date by itself. (You can also download `patches.rvp` from
   [Releases](https://github.com/Krokette/revanced-sync-fixes/releases/latest) and add it as a local file.)
2. Patch Sync the way you did the first time, with the same official patches (**Spoof client** and the others),
   and turn on **Fix Reddit galleries** and **Fix load more comments** from this bundle. Repatching the Sync you
   already have installed works.
3. Install. Sync updates in place and keeps your accounts and settings, as long as the same Manager patched it
   before.

If a patch says it couldn't find what it expected, the APK isn't Sync v23.06.30-13:39 (free). Every patch checks
the code it changes and stops rather than patching the wrong place.

## What changed at Reddit, and what the patches do

Reddit now answers `403 Blocked` to third-party apps on three API endpoints: `/api/info`, `/by_id` and
`/api/morechildren`. The comments endpoint, `/comments/<post>.json`, still works, so the patches rebuild both
features on it:

- **Galleries:** Sync used `/api/info` to get a gallery's images. It now loads the post from
  `/comments/<post>.json`, which carries the same image list.
- **Load more comments:** Sync used to send the ids behind a "View more" row to `/api/morechildren`. It now loads
  the thread under that row (`/comments/<post>/_/<parent>.json`, or the post's top-level comments), keeps the
  comments the row stood for and their replies, and hands them to Sync in the shape it expects.

This is a port of [revanced-rif-fixes](https://github.com/notBrian2/revanced-rif-fixes) by notBrian2, which fixes
the same problems in rif is fun.

## Known limits

- Reddit returns at most 500 comments per request. In very large threads (thousands of comments), the top-level
  comments past that point can't be loaded: their "View more" row disappears.
- A top-level "View more" brings comments with two levels of replies. Deeper replies show **Continue thread**,
  which opens them on their own page.
- The **awards** dialogs still fail. Reddit retired awards.

## Build it yourself

You need a JDK 17 or newer and curl, on Linux, macOS or Windows (Git Bash).

```bash
./build.sh
```

This downloads the pinned compilers into `.tools/` (checked by SHA-256), runs the tests and writes
`build/patches.rvp`. Gradle isn't needed.

To try the bundle on a computer with [revanced-cli](https://github.com/ReVanced/revanced-cli), together with the
official patches:

```bash
java -jar revanced-cli-6.0.0-all.jar patch -p patches-official.rvp -b -p build/patches.rvp -b -o sync-patched.apk sync.apk
```

Run revanced-cli with an OpenJDK build such as Temurin. Oracle's JDK can't sign the APK.

| Path | Contents |
|---|---|
| `patches/` | The two patches (Kotlin): where they hook into Sync |
| `extension/` | `SyncFixes.java`, the code the patches add to Sync |
| `test/` | Tests of `SyncFixes` that run on a computer |
| `bundles/stable.json` | The link ReVanced Manager follows to find the latest release |

## License

[GPL-3.0](LICENSE), like ReVanced and revanced-rif-fixes.
