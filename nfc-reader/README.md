# NFC Reader

An NFC tag reader for the Light Phone III, built with the [Light SDK](https://github.com/lightphone/light-sdk). Tap a tag and the phone reads, displays, and stores its data. This file covers the module's technical detail; see the [main README](../README.md) for an overview.

## Features

- **Scan** NFC tags (NDEF and bare-UID) using the SDK's built-in `LightNfcTapReader`
- **View** tag details: serial number (UID), URI records, text records (with language), binary record count
- **Parse** vCard contact tags into name, phone, and email
- **Save** scan history locally with timestamps (Room database)
- **Copy** tag data by long-pressing it (the SDK has no browser or dialer hand-off)
- **Delete** individual scans or clear all history
- **Actions**: bind an action to a tag's serial number so tapping that tag does something instead of only recording it

  | Type | What it does |
  |---|---|
  | **Webhook** | Sends a GET/POST/PUT request with custom headers, body, and optional skip-SSL for self-signed certs. Includes a **Test** button |
  | **Show note** | Displays a saved piece of text |
  | **Open dialer** | Asks LightOS to open the dialer with a number (from the action or the contact tag) via the SDK's `OpenDialer` service method. Not every LightOS build supports it yet |

## Usage

- Assign an action from the scan result screen (**ACTION**) or the Actions list. When a tag with an action is scanned, the action runs and the result is shown.
- **Ambient scanning:** while the app is open on any screen, tapping a tag runs its action and logs it, with a short result banner on the History screen. NFC is foreground-only: nothing happens while the app is closed or the screen is off (that beep is Android's, not the tool's).

| Screen | Purpose |
|---|---|
| `HomeScreen` | Scan history, ambient reader and result banner, bottom bar: Settings / Scan / Actions |
| `ScanScreen` | Full-screen NFC reader; auto-saves on tap, runs any bound action, shows read failures |
| `TapDetailScreen` | Tag or contact details; long-press to copy, save to file, delete |
| `ActionsListScreen` | All bound actions; tap one to edit |
| `SetupActionScreen` | Create or edit an action for a tag |
| `SettingsScreen` | Invert colors toggle, clear history, version info |
| `ConfirmActionScreen` | Reusable confirmation dialog for destructive actions |

## Installation

### From Releases

See the [main README](../README.md#from-releases).

### From Source

Requires Android Studio with Kotlin/Compose support, or the command line with JDK 17 and the Android SDK.

The module is already wired into `settings.gradle.kts`, and `lighttool.toml` targets a real phone (`serverPackage = "com.lightos"`):

```
./gradlew :nfc-reader:installDebug
```

For the LightOS emulator, set `serverPackage = "com.thelightphone.sdk.emulator"` in `lighttool.toml`.

To add this module to a separate Light SDK checkout, copy `nfc-reader/` next to the `tool/` module and add `include(":nfc-reader")` to that checkout's `settings.gradle.kts`.

**Testing NFC**

- **Light Phone III**: NFC hardware is built in. Open the tool and tap a tag.
- **Emulator**: Android emulators have no NFC hardware, so the tool shows "This phone can't use NFC." This is expected. The rest of the UI (history, settings, detail screens) still works.
- **Other Android devices**: any NFC-equipped Android device can run this for testing via ADB sideload.

## Architecture

Follows the same patterns as [World Clocks](https://github.com/tyshi00/World-Clocks) and other SDK-built tools:

- **MVVM**: `LightScreen` + `LightViewModel` pairs for every screen
- **Room database**: `ScanEntity` + `PreferenceEntity` with DAOs
- **Repository pattern**: `NfcReaderRepository` singleton wrapping all data access
- **SDK theming**: `LightTheme` + `LightThemeController` for dark/light mode
- **SDK components**: `LightTopBar`, `LightBottomBar`, `LightText`, `LightScrollView`, `LightNfcTapReader`

## SDK NFC APIs

| API | Purpose |
|---|---|
| `LightNfcTapReader` (composable) | Full-screen reader with availability handling, error retry, and prompt UI |
| `LightNfcTap` | Tag data: `serialNumber`, `records`, `.uri`, `.text` shortcuts |
| `LightNfcRecord` | Decoded NDEF records: `Uri`, `Text` (with language tag), `Binary` |
| `LightNfcAvailability` | Hardware/permission status: `Ready`, `Disabled`, `PermissionMissing`, `Unsupported` |

## Permission

The tool declares `android.permission.NFC` in `lighttool.toml`, which is on the SDK's allowed permission list. The SDK build plugin automatically adds `<uses-feature android:name="android.hardware.nfc" android:required="false" />`, so phones without NFC are never filtered out.

## Credits

Built on the [Light SDK](https://github.com/lightphone/light-sdk) by The Light Phone (MIT). The original copyright notice is kept in [LICENSE](../LICENSE), and the SDK's own README is kept in [README.light-sdk.md](../README.light-sdk.md).

## License

MIT. See [LICENSE](../LICENSE).

## Disclaimer

Unofficial, independent open-source project — not affiliated with or endorsed by The Light Phone, Inc. Light Phone and Light OS are trademarks of The Light Phone, Inc.
