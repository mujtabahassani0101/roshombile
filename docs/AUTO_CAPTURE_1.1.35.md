# Automatic capture and camera framing — 1.1.35 (4221)

The reported failure was automatic admission, separate from the time required to read a saved photograph. Existing phone logs alternated between `SHARPEN` and `SHOW_QR`; saved-photo benchmarks did not establish whether the shutter would ever fire. The user declined further phone connection for this release. All new validation below is local; there is no new live-camera, phone latency, installation, autofocus, or MLKit throughput claim.

## Automatic admission

- The four guides remain stationary. Small luminance samples compare the expected black square interiors with surrounding white paper at those exact centers. There is no preview-wide paper/corner search. Each aligned target turns green independently.
- The first photograph needs all four aligned targets, clear answer detail, a stable image descriptor, and the 320 ms stability window with at least four frames. It can reach the shutter while preview QR work is pending. This timing is an admission window, not end-to-end camera or OMR latency.
- Subsequent photographs require a current preview identity to distinguish visually similar forms. One valid preview decode can supply that hint when all four targets are aligned; without alignment the tracker retains its two-read requirement. The original-photo native reader and verified roster remain authoritative for recognition and grading.
- The tracker uses the captured frame timestamp, rejects processing delays over 1,800 ms, binds the result to the image descriptor, preserves a candidate across short missed decodes, and rejects contradictory/malformed payloads. Generation changes at pause and shutter invalidate old asynchronous callbacks. Multiple QR payloads clear the tracker.
- The source family, selected question count, native QR, roster binding, duplicate lock, stale-frame checks, and final geometry requirements still apply. An absent QR cannot rearm subsequent captures.

## Focus

The old absolute Laplacian variance and global percentile contrast could confuse faint print with defocus, or treat noisy blur and one remaining sharp edge direction as usable detail. Focus now measures edge width after a small binomial filter, separately along both axes in four answer regions. The weakest region/direction determines readiness. Unprinted target surrounds estimate the noise floor, and raw edge coherence rejects unstructured noise. Header detail cannot substitute for answer-area detail, including upside-down papers.

The local synthetic corpus exercises clear, dim, faint, shaded, moderately softened, sharp/noisy, blurred/noisy, strongly noisy, motion-blurred, partly blurred, dark, displaced, missing-square and blank/noise scenes; all four sensor rotations and small hand tremor are included. It does not model every phone ISP, lens, rolling shutter or lighting condition. The thresholds need field verification on the user's device.

## Straightening integration

`CameraPreparation.cpp` is application code outside the copied engines. After capture it proposes framing only when exactly one four-square configuration matches the known 7 mm print geometry, local surrounding paper contrast, relative square sizes and page proportions. Missing, outlined, wrong-sized or competing registrations decline the proposal. Already tight photographs (proposed crop over 85% of original image area) keep their existing source sampling.

For an accepted proposal:

1. Retain original resolution and color; do not interpolate, denoise, sharpen, change marks, or JPEG-recompress the printed area.
2. Crop the surrounding frame and clear only outside-paper background plus the outer 0.7 mm unprinted margin. A closed dark background border otherwise encloses rail contours and hides them from the unchanged source detector's external-contour pass. Registration squares start 6 mm from the paper edge.
3. Write a temporary lossless BMP. The unchanged source engine still calculates the perspective transform, refines bubbles, and proves every registration row. These framing points are not supplied as an authoritative answer homography.
4. If full native completion fails, read the original photograph. If it remains incomplete, retain the existing document-scanner fallback. Delete temporary framing files in `finally`. Preserve the archived original photograph.

If every fallback still returns successful answers without full registration proof, preserve those native choices and flag them for explicit review. The existing review/signing rules then prevent treating uncertain geometry as a confirmed result.

`cameraFraming` records the proposal, protected polygon, crop and fallback status. Preview diagnostics contain dimensions, age, focus, contrast, noise estimate, aligned-target count, QR readiness and processing duration; they contain no identity payload or credentials. Shutter logs distinguish automatic requests from manual ones and measure the camera save interval independently.

## Evidence

- JVM: 204 deterministic preview frames, 84 accepted and 120 rejected, plus tremor, slow callback/missed-decode, duplicate, generation/reset, camera-plane and activity regression checks. Fixtures are bundled under test resources, so unit tests do not require Python or generated output directories.
- Native replay: 39 generated, known-answer photographs across 40/75/100 questions, rotations, print/pen colors, offset and perspective. All 39 prove the complete native registration rows. 2,794 of 2,795 known choices match. The remaining very faint mark in `40q-05` is also returned as blank by the existing source reader; it is a known limitation, not counted as correct. No case loses a previously correct choice.
- The two previously incomplete offset 75-question cases and a new perspective 75-question case now read all 75 choices each, accounting for 225 recovered choices in this corpus.
- Six negative framing cases decline the proposal: missing square, blank, square outlines, wrong-size squares, two pages, random noise. An EXIF-6 image retains orientation and all 75 known choices. A QR placed in the answer region is rejected.
- Protected printed pixels are compared byte-for-byte against the decoded source image, and original-file hashes are unchanged. The background clearing is explicit; the entire output bitmap is not claimed pixel-identical to the input.
- The separate 27-case calibrated-header comparison retains the same decisions. Source audit checks all 869 copied files and both exact engine libraries inside the APK.

Reports and build logs: `output/auto-capture-1.1.35/`. ARM64 APK: `output/roshd-1.1.35-arm64.apk`.

## Reproduce

Use Java 21 with the Android SDK/NDK configured. Unit replay fixtures are already in `app/src/test/resources/preview-replay.zip`; `tools/generate_preview_replay.py` regenerates them from the existing synthetic paper assets using NumPy/OpenCV.

```bash
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest :app:testReleaseUnitTest \
  -Pabis=arm64-v8a -PqaBuildType=release \
  -PqaRunner=com.haseebullahjan.roshd.qa.ScanPerformanceTest \
  --tests '*PreviewReplayTest' --tests '*PreviewPageIdentityTest' \
  --tests '*GuidedScanFrameTest' --tests '*AutoCaptureGateTest' \
  --tests '*PreviewLumaFrameTest' --tests '*CameraNormalizationPolicyTest' \
  --tests '*CameraFramePackerTest' --tests '*ScannerPreparationRegressionTest' \
  --tests '*AndroidCrashRegressionTest'

cmake --build output/source-port-1.1.31/build --target roshd_engine_adapter -j2
python3 tools/check_camera_preparation.py \
  --adapter output/source-port-1.1.31/build/libroshd_engine_adapter.so \
  --output output/auto-capture-1.1.35/native-replay
python3 tools/audit_source_engine_port.py \
  --apk output/roshd-1.1.35-arm64.apk \
  --report output/auto-capture-1.1.35/source-audit.json
```

Host native tests require the same OpenCV/ZXing shared-library environment as the earlier source-port tests. `PreviewPerformanceTest` remains a synthetic MLKit fixture benchmark; the misleading rectified-and-redrawn private-photo pseudo-preview has been removed. The new release instrumentation APK compiles, but was not run on a phone.
