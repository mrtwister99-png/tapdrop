# Odhoz Cross-Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a consistent “Odhoz” text handoff tab to Android and Windows, shared over the authenticated local HTTP API, with copy-all support and matching TapDrop visual tokens and icons.

**Architecture:** Store the current text in `Shared` on the PC, expose `GET /odhoz` and `PUT /odhoz` through the existing Axum server and token middleware, and poll or explicitly submit text from Android. Android owns an editable text state; the PC view mirrors the same state and allows copy-all and native text selection.

**Tech Stack:** Kotlin + Jetpack Compose + OkHttp; Rust + Tokio + Axum + egui; Android Gradle; Cargo; Inno Setup.

**Spec:** User-approved scope from 2026-11-23.

## Global Constraints

- Android and PC retain the existing silver/gray/near-black/near-white visual hierarchy.
- #4058c7 is the primary blue accent and #2eb365 is the success/connected green.
- Synchronization is bidirectional: either side may replace the shared Odhoz text.
- Text is limited to 1 MiB and is never stored on disk as a history item.
- The Android tab bar keeps existing destinations and adds one “Odhoz” tab.
- The PC top navigation adds “Odhoz” beside Přehled.
- Copy-all and native text selection/copy remain available on both platforms.

## Review Focus

- Empty text must clear the shared Odhoz text, not be ignored. Test `PUT /odhoz ""` returns and stores an empty string.
- Oversized text must be rejected with HTTP 413 rather than silently truncating. Test text above 1 MiB.
- Android network failure must preserve locally typed text and show a visible error. Implementation owns Android Odhoz screen behavior.
- PC text updates must repaint immediately and not overwrite unsaved local edits unexpectedly. Test state update separately from UI.
- Release artifacts must use the new app icon. Verify launcher/tray/Windows icon resources in builds.

---

### Task 1: Add shared Odhoz state and HTTP API tests

**Files:**
- Modify: `pc-rust/src/state.rs`
- Modify: `pc-rust/src/server.rs`
- Test: `pc-rust/src/server.rs`

**Interfaces:**
- `Odhoz` state: `Shared::odhoz_text() -> String` and `Shared::set_odhoz_text(text: String)`.
- `GET /odhoz` returns JSON `{ "text": "..." }`.
- `PUT /odhoz` accepts JSON `{ "text": "..." }`, `200` for accepted text, `400` for invalid JSON, `413` above `MAX_ODHOZ_BYTES`.

- [ ] **Step 1: Add Rust unit tests for text length and shared text replacement.**

```rust
fn odhoz_rejects_text_over_limit() {
    assert_eq!(validate_odhoz_text(&"x".repeat(MAX_ODHOZ_BYTES + 1)), Err(StatusCode::PAYLOAD_TOO_LARGE));
}

fn odhoz_accepts_empty_text() {
    assert_eq!(validate_odhoz_text(""), Ok(()));
}
```

- [ ] **Step 2: Run `cargo test odhoz` and confirm tests fail because the Odhoz symbols do not exist.**

Run: `cargo test odhoz`
Expected: FAIL on unresolved `MAX_ODHOZ_BYTES`, `validate_odhoz_text`, or shared text methods.

- [ ] **Step 3: Add private `MAX_ODHOZ_BYTES: usize = 1_048_576`, `odhoz_text: String`, and Odhoz methods to `Shared`/`Inner`.**

- [ ] **Step 4: Add `validate_odhoz_text` and authenticated routes `GET /odhoz`, `PUT /odhoz` in `server.rs`.**

- [ ] **Step 5: Run `cargo test odhoz` and confirm it passes.**

### Task 2: Wire the Android Odhoz API and test it

**Files:**
- Modify: `android-app/app/src/main/java/cz/tapdrop/app/net/Api.kt`
- Test: `android-app/app/src/test/java/cz/tapdrop/app/net/ApiOdhozTest.kt`

**Interfaces:**
- `suspend fun getOdhoz(): String`
- `suspend fun setOdhoz(text: String)`
- Empty text is sent and returns successfully.

- [ ] **Step 1: Add JVM test infrastructure only if absent, plus an HTTP fixture test proving GET and PUT paths and JSON payload.**

- [ ] **Step 2: Run `./gradlew testDebugUnitTest --tests '*ApiOdhozTest'` and confirm it fails before methods exist.**

- [ ] **Step 3: Implement `getOdhoz()` and `setOdhoz(text)` in `Api.kt` using the existing authenticated OkHttp client.**

- [ ] **Step 4: Run the focused Android test and confirm it passes.**

### Task 3: Add the Android Odhoz tab and copy action

**Files:**
- Modify: `android-app/app/src/main/java/cz/tapdrop/app/ui/AppRoot.kt`
- Modify: `android-app/app/src/main/java/cz/tapdrop/app/ui/Screens.kt`
- Modify: `android-app/app/src/main/res/values/colors.xml`
- Modify: Compose theme file(s) for Material 3 palette

**Interfaces:**
- Tab route name is `"odhoz"` and title is `"Odhoz"`.
- The screen contains a visible label, multiline `OutlinedTextField`, copy button, and visible sync/error feedback.
- `getOdhoz()` is loaded after pairing and `setOdhoz(text)` is called by an explicit “Uložit / Odeslat text” action.
- Copy-all uses Android `ClipboardManager`.

- [ ] **Step 1: Add a Compose test or Android UI state test for an empty Odhoz value and copy availability.**

- [ ] **Step 2: Run the smallest Android test target and confirm it fails before the Odhoz screen exists.**

- [ ] **Step 3: Add the tab, route, screen, API calls, local error handling, and copy-all behavior.**

- [ ] **Step 4: Replace ad-hoc app colors with the unified semantic palette while preserving current component behavior.**

- [ ] **Step 5: Run `./gradlew testDebugUnitTest assembleDebug` and confirm both succeed.**

### Task 4: Add the PC Odhoz tab and copy action

**Files:**
- Modify: `pc-rust/src/ui.rs`
- Modify: `pc-rust/src/state.rs`

**Interfaces:**
- Add `Tab::Odhoz`.
- Add `TapDropApp.odhoz_text: String`.
- Odhoz tab contains a multiline text editor, “Zkopírovat vše”, and status text for shared API state.
- Native egui text selection remains enabled; `ctx.copy_text(text)` copies the whole field.

- [ ] **Step 1: Add Rust unit test for copy payload and state normalization, or test the state replacement method.**

- [ ] **Step 2: Run `cargo test odhoz` and confirm the PC UI state test fails before implementation.**

- [ ] **Step 3: Implement the Odhoz tab, shared text field state, copy-all action, and periodic refresh from `Shared::odhoz_text()`.**

- [ ] **Step 4: Apply the palette constants to egui surfaces, accent, success, and copy controls; keep existing hierarchy.**

- [ ] **Step 5: Run `cargo test` and `cargo build --release` and confirm both succeed.**

### Task 5: Update Android and Windows icons

**Files:**
- Modify: `android-app/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `android-app/app/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `android-app/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `pc-rust/src/ui.rs` `make_icon`
- Modify: `pc-rust/build.rs` if the executable icon is generated from a Windows `.ico`

**Interfaces:**
- Android adaptive icon uses #4058c7, #2eb365, silver/white shapes, and the existing circular TapDrop mark.
- Windows taskbar/tray/window icon uses the same geometry and palette.

- [ ] **Step 1: Update vectors and `make_icon` with the agreed palette.**

- [ ] **Step 2: Build Android and PC release artifacts and inspect icon resources.**

### Task 6: Document and execute release builds

**Files:**
- Modify: `README.md`
- Modify: `installer/tapdrop.iss`

**Interfaces:**
- Release Android command: `.\gradlew.bat assembleRelease`
- Release APK path: `app\build\outputs\apk\release\app-release.apk`
- Release PC command: `cargo build --release`
- Windows EXE: `target\release\TapDrop.exe`
- Installer command: Inno Setup compile with `ISCC.exe installer\tapdrop.iss`

- [ ] **Step 1: Update README release and Odhoz usage sections.**

- [ ] **Step 2: Run release builds and verify generated files exist.**

- [ ] **Step 3: Provide exact PowerShell commands and generated artifact paths in the final response.**
