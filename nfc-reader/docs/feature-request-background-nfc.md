# Feature request notes: NFC taps when the tool is not in the foreground

Working notes for a Light SDK issue. Not yet filed. The Light repo expects issue text
in a human's own words, so rewrite before posting.

## Problem

NFC Reader binds actions (a Home Assistant webhook, a note, open dialer) to a tag's
serial number. Testers want to tap a tag on the way into the house or car and have the
action run without opening the tool first.

Today that only works while NFC Reader is the visible app. `LightNfcTapReader` /
`enableReaderMode` is tied to the foreground activity: `ON_RESUME` arms it, `ON_PAUSE`
disarms it (Android only hands the NFC radio to the foreground app). The v1.1.0 build
added an ambient reader so a tap works from any screen inside the tool, but backgrounded
or screen-off, Android's own handler takes the tap and the tool never sees it.

## Why a tool cannot solve this itself

- `AndroidManifest.xml` is generated in full by `ManifestGenerator` from
  `LightToolMetadata` (id, label, version, permissions, capabilities, orientation,
  serverPackage). A user-supplied manifest is rejected by the plugin.
- The generated `LightActivity` has only a `MAIN` / `LAUNCHER` intent-filter. There is no
  way to add an `NDEF_DISCOVERED` / `TECH_DISCOVERED` / `TAG_DISCOVERED` filter, which is
  the only mechanism that delivers a tap to a non-foreground app.
- `LightActivity` has no `onNewIntent` or NFC intent parsing.
- `LightWork` / `@LightJob` jobs are deferred and have no NFC access.
  `LightEntryPoint.onPushNotification` is push only.

## Asks, smaller first

### A. Tag-routed launch via a capability (preferred)

Extend the capability mechanism the way `DETACHED_AUDIO` already works: a capability in
`lighttool.toml` that makes `ManifestGenerator` emit extra manifest entries.

- `capabilities = ["nfc"]` plus a declared URI scheme or MIME type, e.g.
  `nfcScheme = "myapp"` or `nfcMimeType = "application/vnd.mytool"`.
- `ManifestGenerator` emits a matching `<intent-filter>` on `LightActivity`.
- The SDK routes the incoming tag to the tool, most likely as a new
  `LightEntryPoint.onNfcTap(tap)` or an intent the client library parses into
  `LightNfcTap`.
- Tags carry a small routing record (an Android Application Record or a URL) instead of
  being matched purely by UID in-app. Fine for this use case; the user writes the tag.

This is a bounded change: it reuses the existing capability to manifest pattern and does
not add a background service or new permissions.

### B. Background or dispatch NFC (broader)

Let a tool register to receive taps while not foreground, or be launched by a tap without
a routing record on the tag (UID match kept in-app). Larger surface, more review, but
covers cases where the tag content cannot be controlled.

## Supporting context

- Home Assistant users are the obvious audience: NFC tags as physical automation
  triggers is a common HA pattern, and doing it on a Light Phone keeps the phone in a
  drawer rather than pulled out and unlocked.
- Happy to test on real LP3 hardware.
