# Moyu Android v0.0.2 integration

This project keeps the HTML presentation layer separate from Android glue:

- `src/` renders only the host-provided view model and emits versioned UI intents.
- `android/app/src/main/java/com/moyu/remote/` owns SQLite history, Keystore-wrapped node secrets, EasyTier lifecycle, REST/WebSocket transport, reconnect and view projection.
- Backend traffic is issued only by the native client through EasyTier's loopback SOCKS5 portal. The WebView CSP sets `connect-src 'none'` and the WebView accepts only bundled assets from its private application origin.
- EasyTier runs in no-tun + smoltcp mode as a normal foreground service. The app does not request Android VPN or storage permissions.

## Supported package

- Application id: `com.moyu.remote`
- Version: `0.0.2` (`versionCode 2`)
- Minimum Android: 8.0 / API 26
- ABI: `arm64-v8a`
- Bundled EasyTier: v2.6.4, dynamically linked as a separate LGPL shared library

## Build

Use JDK 11, Android SDK 34 and the checked-in Gradle wrapper:

```powershell
npm ci
npm run build
npm run check
cd android
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon
```

The UI build is copied automatically into `app/src/main/assets/ui`. Preview fixtures are excluded from the APK. The two arm64 native libraries must exist under `android/app/src/main/jniLibs/arm64-v8a/`; `libeasytier_ffi.so` corresponds to upstream EasyTier tag `v2.6.4` at commit `8428a89d2dabc94c97d370ec607c6ca142473626`.

## First connection

1. On the PC, run `moyu init` once and configure the relay node. Add any Claude `.env` profiles and Codex `CODEX_HOME` profiles as described by `moyu -help`.
2. Start Moyu, then run `moyu pair`. The PC prints `<8-character-code>:<gateway-port>`.
3. In Android, open Nodes, choose Pair, enter a display name, the same relay node and the pairing string.
4. The native layer joins the short-lived pairing overlay, receives the real network name/secret and bearer token, stores secrets with Android Keystore, then reconnects through the real overlay.

Manual setup remains available in the native dialog for diagnostics or recovery. It requires the relay, real EasyTier network name/secret, mobile/backend overlay IPs, gateway port and bearer token. Leaving an existing secret field blank preserves its current value.

## Runtime behavior

- Local sessions, timeline, drafts, theme and node metadata are kept in SQLite and remain readable without a connection.
- Secret values never enter SQLite or the WebView.
- The active session is the single live WebSocket subscription, matching the backend protocol. Switching sessions resubscribes with its last sequence and replays gaps; initial connection synchronizes all known remote sessions.
- Offline input is saved only as a draft and is never automatically replayed.
- Diff and directory reads are explicit authenticated backend operations. Adapter profile/model selection is frozen when a session is created.
- Phone-to-backend RTT is measured by native ping. Backend/CLI aggregate timing and relay timing are displayed only when the backend reports them.

The distributable APK produced for local testing is signed with a test key. Replace it with a project-owned release keystore before public distribution.
