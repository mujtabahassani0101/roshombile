# Historical engine integration notes

These notes record earlier client integration and calibration work. Current Kotlin build and QA commands are in the parent README and the root `qa/VALIDATION.md`. Earlier client commands below are historical.

# Young Scientist mobile OMR integration — r26 highest-percentage answer

The production path now consists of four source modules: `HeaderEngine`,
`T1AnswerEngine`, `T2AnswerEngine`, and `T3AnswerEngine`. A page runs the header
once and exactly one fixed-family question engine. QR retries receive only a
header crop. Normalization and the question engines never decode QR again.
Bubble thresholds are retained. Shared
geometry and measurement code is packaged in the existing native library.
See [the four-engine design](docs/FOUR_ENGINES.fa.md) for the handoff and tests.
The current revision is `omr-mobile-api-v2-four-engines-r26-highest-fill`.

r23 avoids repeating image morphology for incompatible older prints only when
the current primary print has complete rails, at most 1.5 px maximum error and
0.75 px header/answer RMS. Every alternate print is first fitted to all observed
dots; it must exceed both 6 px overall RMS and 8 px header RMS to be excluded.
Missing dots, equivalent layouts, incorrect direction hints and diagnostic
exports keep the complete search. Selection uses registration geometry alone.
The same normalized pixels and mark thresholds feed the answer reader.
`omr_header_orientation_speed_regression` verifies exact options, confidence
and fill ratios across rotations and guards each fallback above.

Answer selection now follows the requested single-answer policy: every non-blank
question selects exactly one option with the highest `rawFillRatios` percentage
(the measured `fillRatio`). Equal percentages use greater paper-relative ink
darkness, then lower option number. Binary/annulus measurement and print-baseline
calibration remain available to distinguish blanks; rows with several broad
fills cannot collapse to blank when their common baseline subtracts to zero.
The answer reader no longer emits MULTI or AMBIGUOUS. Student-code digit reading
and previously saved result formats keep their existing compatibility behavior.
Confidence still records weak separation and is not raised to conceal close ties.

The answer-reader regression checks blank rows, two/four equal fills, a broad
pale fill whose percentage exceeds a darker partial fill, darker tie-breaking,
and 192 lighting/gamma/grayscale/JPEG cases. `tools/test_omr_camera_variants.py`
checks the exact APK on the original photo, the user's later unmodified photo,
and camera-like variants at 2000px/2600px, requiring exactly one maximum-percentage
answer. `tools/test_omr_faint_secondary.ps1` also verifies single selection when
secondary choices are darkened. Both reported Q14/Q15 answers are checked on
actual packaged native output, not reconstructed Dart observations.

The reader also corrects paper measurement on magenta row bands. Missing
non-magenta samples no longer imply white paper at gray 255. White cutout
fringes establish a locally varying paper reference, including under shadows;
the row fit uses an upper envelope supported by two circles to reject ink as
paper. Magenta JPEG bleed must have real local contrast and broad coverage to
contribute ink. The 12% relative contrast rule has a 12-gray floor only on stable
paper or verified cutouts; steep illumination ramps retain the 20-gray floor.
Pixel contrast thresholds are unchanged; question decisions use the r26 policy above. The striped-sheet
regression checks 288 questions with black/magenta printing, JPEG, gradients,
deep shadows, faint pencil, blue/purple ink, blanks and two/four marked choices.

The current 100/60/40-question PDF artwork is the primary template geometry;
older printed layouts remain explicit variants. Optional registration
`rightRowY` coordinates describe the new 60-question sheet's right answer dots,
printed 4pt higher than the left dots. When omitted, both rails use `rowY`.
Both complete-rail and answer-only homographies use the appropriate side's
coordinates, so answer bubbles are not sheared to compensate for a print offset.

r21 runs one persistent header FIFO/worker and three independent answer
FIFOs/workers, one each for T1/T2/T3. The header publishes its validated typed
result to the matching queue and immediately scans the next page. All native
calls share this pipeline, including concurrent single-page Flutter requests.
Workers hold locks only while pushing/popping tasks. Results and errors retain
input order; a rejected header never enters an answer queue. Diagnostic files
use an atomic suffix to avoid overwriting concurrent exports.

Flutter feeds up to 12 local reads concurrently and serializes result commits.
Each request has its own progress callback and metrics, and template installation
is shared while initializing. New captures wake the dispatcher while earlier
readers are active. Native `pipeline` timestamps use a monotonic batch clock and
include queue waits and worker thread IDs. Batch `processingTimeMs` is elapsed
wall time; `sheetProcessingTimeMs` is the sum of per-page end-to-end latencies,
including queue waits, which can exceed wall time.

`omr_parallel_engine_queues_regression` checks measured three-way overlap,
header/answer overlap, per-family FIFO ordering, 800 known answers, failed-page
ordering, unique exports, and worker reuse across nine concurrent requests.
`tools/test_omr_parallel_queues.py` runs Android regressions and compares all
answers, fill ratios, confidence values and identities through the C ABI.
It compares with r20 for symmetric layouts and checks current-engine
repeatability for asymmetric layouts unsupported by r20.
Pass `--apk <path>` to test the exact packaged x86_64 library.

r20 first verifies the header's proposed direction against complete registration
rails before skipping repeated orientation probes. Alternate T3 layouts are
compared in that direction and retained only when header and answer reprojection
both verify the layout; otherwise the original four-direction search runs.
Template row-pitch medians are computed once per rail match instead of once per
candidate anchor transform. Mark thresholds, template coordinates, QR decoding,
and bubble measurements are unchanged. Performance comparisons use alternating
before/after runs with exact answer, fill-ratio and confidence comparisons.
If a difficult page needs the original orientation fallback, its first probe's
decoded input, rails and normalized pixels are reused only when the selected
source, layout and rotation match. This cache is local to one normalization
call; a missing-rail-dot regression checks it against a separate full read.

The following notes describe the retained QR contract and earlier calibration
work; r19 replaces the earlier per-template QR probes and repeated QR reads.

Revision r17 introduced the compact QR contract retained below.
Routing requires the complete `Tn:studentCode:identifier` printed format, using
ASCII colons and exact case. Current identifiers contain exactly eight ASCII
letters or digits, for example `T3:213327:rZeGOHtv`; legacy uppercase `S2` plus
18 Crockford characters remains accepted. Bare codes remain valid only for
internal template metadata through `fromCode`; they are not printed
credentials. All three assets enforce the same complete format. The Flutter
app authorizes identities only by an exact signed `sheetQr` match and never
sends the printed identifier as a token.

The T3 asset retains its primary geometry and includes the new `t3-2x20-2026`
calibration. Automatic rail normalization compares their reprojection and QR
evidence before reading answers. It does not score answers to choose a print
layout. Explicit corner-anchor requests retain the primary calibration.
Variant objects cannot change the physical QR binding or question ordering;
credentials, read thresholds and live options are inherited from the parent.
Only T3 has an additional geometry. T1/T2 and original T3 coordinates remain
unchanged; QR validation is stricter in every asset. The supplied 2x20 PDF is
tested from independent vector coordinates.
Multi-template diagnostics prioritize an actually decoded QR over a geometric
fit that found no QR. The reported 60-question PDF reproduced the r13 ranking
error; the native prefix regression now covers unsupported S2 values on all
three physical layouts. That diagnostic fix preserves answer classification.
Only complete decoded T1/T2/T3 QR credentials select a layout. A decoded bare
S2/C2, unknown or lowercase T prefix reports `UNSUPPORTED_SHEET_TYPE` with
`sheet_type_qr_unsupported_prefix`; it never falls back to geometric routing.
Missing/unreadable QR remains distinct. The 100-question PDF reported on
2026-10-18 contains only an S2 alias and must be reissued with a proper T1 QR.
Its physical grid is compatible; positive regression images replace only the
QR with a synthetic T1 value and do not authenticate a real student.

The r12 dark-row answer correction below is retained. Primary calibrations and
trusted identity matching are unchanged. The offline lab
also checks the complete decoded QR and matching T family before displaying observations.

The active engine is the complete `omr-mobile-engine/` source package supplied in
`omr-mobile-engine-full-t1-t2-t3-r11.zip`. All three calibrations are retained
unchanged. The r12 answer-reader correction handles white circles on dark bands:
two white interiors must independently show strong contrast with their printed
surroundings before their paper level is used for ink comparison across the row.
The reported real T3 photo and synthetic empty/multiple/color/grayscale rows
cover this correction. A three-file integration patch to
`MobileOmrBatch.cpp` and `utils/Json.{cpp,hpp}` restores root-only single-image
detection: upstream r11 incorrectly treats the first nested `pages[].imagePath`
as a single-image request and silently omits every following page. The patch
reuses the previously verified JSON helper from this app's former engine.
ZIP build directories and precompiled host artifacts are excluded.
`omr-live-detector/`, the older top-level templates and TypeScript helper are
historical references; the app build does not link that previous engine.

The wrapper `CMakeLists.txt` builds the active package with the existing vendored
OpenCV 4.12.0 Android SDK and ZXing-C++ 3.1.0, using C++20. Flutter uses the C ABI
through FFI on a worker isolate. The reference JNI bridge is disabled.

| QR prefix | Physical template | Capacity |
| --- | --- | --- |
| T1 | yse_omr_a5_t1_100q_v2.json | 100 |
| T2 | yse_omr_a5_t2_60q_v1.json | 60 |
| T3 | yse_omr_a5_t3_40q_v1.json | 40 |

The current engine revision is `omr-mobile-api-v2-four-engines-r26-highest-fill`; C ABI/schema version is
2. The ABI remains `omr_mobile_api_version`, `omr_mobile_engine_revision`,
`omr_mobile_process_json`, and `omr_mobile_free_string`.

Flutter installs all three exact template assets in a revision-specific private
directory and sends `templateJsonPaths`. Native QR preflight chooses the physical
layout before reading bubbles. Full per-question observations are requested;
debug and normalized-image exports are disabled because review uses the archived
original. Large input images are decoded into temporary copies near 2600px and
removed after native processing. Results always reference the original image.

The native physical question count is retained per page. Only after the full QR
resolves against the signed roster does Flutter apply that exam's actual active
question count (1–40 => T3, 41–60 => T2, 61–100 => T1). The T1/T2/T3 sheets have no
student-code bubble grid; identity comes from the exact full QR and trusted roster.
A missing, unsupported or contradictory sheet type remains a failed/reviewable
page and cannot be finalized by manually selecting an identity or answers.
Historical saved results continue to support their original 120-question data.

Build the app using `flutter build apk --release`. The configured Android ABIs
are arm64-v8a and x86_64, with minSdk 24. The vendored OpenCV and libc++ shared
libraries must be packaged alongside `liblajvard_omr_mobile.so` for each ABI.
The existing Flutter platform support remains Android-only.

Run the native regressions on an x86_64 Android emulator:

```powershell
.\tools\test_omr_r11_android.ps1 -Serial emulator-5554
```

This builds eight supplied regressions, a dark-band regression, a strict QR-prefix
regression, a T3 geometry compatibility regression and a packaged C ABI test,
which renders all three layouts, verifies 200 exact answer choices including
rotated T2/T3 inputs, and retains a failed fourth page. Logs and generated
fixtures are saved in `reports/omr-r11/native-tests/`.

After that suite has generated its fixtures, test the actual library extracted
from the release APK through a pure C loader (no test-only ZXing writer links):

```powershell
.\tools\test_omr_r11_apk.ps1 -ApkPath .\YSE-1.0.12-build-79-final.apk
```

Run the Flutter integration coverage with:

```powershell
flutter test test/omr_three_templates_test.dart
flutter analyze
flutter test
```

See `omr-mobile-engine/docs/API.md` for the upstream native contract and
`omr-mobile-engine/templates/README.md` for calibration details.
