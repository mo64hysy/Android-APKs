# KeyMouse APK build

The GitHub Actions workflow `Build KeyMouse APK` builds a debug APK when files under `KeyMouse/` change.

The APK is uploaded as the `KeyMouse-debug-apk` workflow artifact.

## Recent fixes
- Right-side short taps, including Backspace, are sent with a short press interval so the HID host can register them reliably.
- Mouse gesture completion clears temporary key-press visuals.
- Right Shift uses the same width as Left Shift.
