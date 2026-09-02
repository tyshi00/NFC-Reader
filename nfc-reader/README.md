# NFC Reader — a LightOS Tool

A minimal NFC tag reader for the Light Phone III, built with the [Light SDK](https://github.com/lightphone/light-sdk).

Tap an NFC tag → Light Phone reads the tag → displays and stores the data.

## What it does

- **Scan** NFC tags (NDEF and bare-UID) using the SDK's built-in `LightNfcTapReader`
- **View** tag details: serial number (UID), URI records, text records (with language), binary record count
- **Parse** vCard contact tags into name / phone / email
- **Save** scan history locally with timestamps (Room database)
- **Copy** tag data by long-pressing it (the SDK has no browser or dialer hand-off)
- **Delete** individual scans or clear all history

## Actions

An **action** is bound to a tag's serial number, so tapping that specific tag does
something instead of just recording it:

| Type | What it does |
|---|---|
| **Webhook** | Sends a GET/POST/PUT request — custom headers, body, optional skip-SSL for self-signed certs. Has a **Test** button. |
| **Show note** | Displays a saved piece of text. |
| **Open dialer** | Asks LightOS to open the phone dialer with a number (from the action, or from the contact tag itself). Uses the SDK's `OpenDialer` service method — not every LightOS build honours it yet. |

Assign one from the scan result screen (**ACTION**) or the Actions list. When a tag
with an action is scanned, the action runs and the result is shown.

**Ambient scanning:** while the app is open — on any screen, not just Scan — tapping a
tag runs its action and logs it, with a short result banner on the History screen.
NFC is still foreground-only: nothing happens while the app is closed or the screen is
off (that beep is Android's, not the tool's).

## Screens

| Screen | Purpose |
|---|---|
| `HomeScreen` | Scan history, ambient reader + result banner, bottom bar: Settings / Scan / Actions |
| `ScanScreen` | Full-screen NFC reader; auto-saves on tap, runs any bound action, shows read failures |
| `TapDetailScreen` | Tag or contact details; long-press to copy, save to file, delete |
| `ActionsListScreen` | All bound actions; tap one to edit |
| `SetupActionScreen` | Create or edit an action for a tag |
| `SettingsScreen` | Invert colors toggle, clear history, version info |
| `ConfirmActionScreen` | Reusable confirmation dialog for destructive actions |

## Setup

### Prerequisites

- Android Studio with Kotlin/Compose support
- A clone of the [Light SDK](https://github.com/lightphone/light-sdk)

### Integration

1. Copy the `nfc-reader/` directory into your Light SDK checkout, alongside the existing `tool/` module.

2. In `settings.gradle.kts`, add:
   ```kotlin
   include(":nfc-reader")
   ```

3. Update `lighttool.toml` if needed:
   - For **emulator** testing: `serverPackage = "com.thelightphone.sdk.emulator"` (default)
   - For a **real LP3**: `serverPackage = "com.lightos"`

4. Build and run:
   ```
   ./gradlew :nfc-reader:installDebug
   ```

### Testing NFC

- **Real Light Phone III**: NFC hardware is built in. Just open the tool and tap a tag.
- **Emulator**: Android emulators don't have NFC hardware, so the tool will show "This phone can't use NFC." — this is expected. The rest of the UI (history, settings, detail screens) still works.
- **Other Android devices**: Any NFC-equipped Android device can run this for testing via ADB sideload.

## Architecture

Follows the same patterns as [World Clocks](https://github.com/nicholasgasior/worldclocks-lightos) and other SDK-built tools:

- **MVVM**: `LightScreen` + `LightViewModel` pairs for every screen
- **Room database**: `ScanEntity` + `PreferenceEntity` with DAOs
- **Repository pattern**: `NfcReaderRepository` singleton wrapping all data access
- **SDK theming**: `LightTheme` + `LightThemeController` for dark/light mode
- **SDK components**: `LightTopBar`, `LightBottomBar`, `LightText`, `LightScrollView`, `LightNfcTapReader`

## SDK NFC APIs used

| API | Purpose |
|---|---|
| `LightNfcTapReader` (composable) | Full-screen reader with availability handling, error retry, and prompt UI |
| `LightNfcTap` | Tag data: `serialNumber`, `records`, `.uri`, `.text` shortcuts |
| `LightNfcRecord` | Decoded NDEF records: `Uri`, `Text` (with language tag), `Binary` |
| `LightNfcAvailability` | Hardware/permission status: `Ready`, `Disabled`, `PermissionMissing`, `Unsupported` |

## Permission

The tool declares `android.permission.NFC` in `lighttool.toml`. This is on the SDK's allowed permission list. The SDK build plugin automatically emits the corresponding `<uses-feature android:name="android.hardware.nfc" android:required="false" />` so phones without NFC are never filtered out.

## License

Same as the Light SDK — see the SDK repository for license terms.
