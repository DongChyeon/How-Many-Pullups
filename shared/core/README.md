# shared:core

Pure Kotlin. Contains the D2 rep detector (`pullup.core.rep`). It has no CameraX, MediaPipe or Android dependencies (D4 §2.2).

Targets:
- `jvm()`: added so the detector can be unit-tested and evaluated on a desktop, with no device or emulator. D4 §2.1 lists the jvm target as "for tests".
- `android` (the `com.android.kotlin.multiplatform.library` plugin): lets `composeApp` depend on this module.

Commands:
- `./gradlew :shared:core:jvmTest` runs the D2 §13 golden cases and the calibration tests on synthetic landmarks.
- `./gradlew :shared:core:evalLandmarks -Pdir=docs/private/dataset/landmarks [-Pmeta=<dir>]` replays landmark dumps and compares the counts with the ground truth. It only prints results and writes no files. Videos whose meta has `"excludeFromEval": true` are skipped and listed as excluded. `-Ptrace=<file substring>` prints a per-frame trace (raw elbow angle, clearance h, effective θ_ext, state).
