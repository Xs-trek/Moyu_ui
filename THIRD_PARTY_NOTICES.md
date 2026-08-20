# Third-party notices

## EasyTier 2.6.4

The Android package dynamically links the unmodified `libeasytier_ffi.so` built from
[EasyTier v2.6.4](https://github.com/EasyTier/EasyTier/tree/v2.6.4). EasyTier is licensed
under GNU Lesser General Public License version 3. A copy of that license is included in
the APK at `assets/licenses/EasyTier-LGPL-3.0.txt`; the incorporated GPLv3 text is included
at `assets/licenses/GPL-3.0.txt`.

The EasyTier shared library remains a separate, replaceable APK native library. The exact
corresponding source is the upstream v2.6.4 tag (commit
`8428a89d2dabc94c97d370ec607c6ca142473626`).

The Android package expects the following verified arm64 files under
`android/app/src/main/jniLibs/arm64-v8a/`:

- `libeasytier_ffi.so` — SHA-256 `C4B7B42C6EB809869AAD9CFAFD2AE5877BFAC0AB827416FB6F2F525F457407D5`
- `libeasytier_android_jni.so` — SHA-256 `B92A64620D084F9511AD03701CE9A9F62FF23268B8137B6A8337D367FE287BA9`

`scripts/verify-native-libs.ps1` verifies these exact inputs before release packaging.

## OkHttp 3.14.9 / Okio

Copyright Square, Inc. Licensed under the Apache License 2.0.

## Web UI build toolchain

Vite, Rollup and esbuild are MIT-licensed build-time dependencies. They are not front-end
runtime frameworks and no remote package code is loaded by the WebView.

## Platform identification marks

The package includes local Anthropic and OpenAI SVG marks solely to identify compatibility
with Claude Code and Codex CLI. Anthropic, Claude, OpenAI, Codex and their associated marks
belong to their respective owners. Their inclusion does not imply sponsorship, endorsement or
affiliation. The assets were obtained from the respective official brand/press materials and
are not granted for unrelated reuse by the Apache-2.0 software license.
