# Roshd — native Android

Kotlin Android application for [Roshd](https://roshd.azmoonic.com/), version 1.1.38 (4224).

Version 1.1.38 adds registered ink verification using the unchanged source answer reader on color/channel representations. Recovered faint or colored marks and conflicting readings require explicit question confirmation. Original photographs, source engine binaries, QR identity and registration geometry remain intact. See [ink verification and validation](docs/INK_VERIFICATION_1.1.38.md).

Version 1.1.37 consolidates every screen on Study Glass, removes Organic UI, palettes, resources and the first-use/settings theme picker, and retains light/dark appearance and Dari/Pashto. Existing Organic preferences open Study automatically. Exam search and pagination now use the same Study cards.

Version 1.1.36 adds confirmed local deletion of an entire scanned sheet from history and review, including its local upload queue and associated photos. In-flight camera results cannot restore a deleted sheet; file cleanup waits for workers and resumes after process restart. Previously delivered server results remain on the server. See [scan deletion](docs/SCAN_DELETION_1.1.36.md).

Version 1.1.35 separates the first automatic shutter from asynchronous preview QR decoding, checks alignment at the four fixed guide centers, and measures focus in four answer regions along both axes. Application framing removes excessive background before the unchanged source normalizer; original-photo and document-scanner fallbacks remain. Local replay covers 204 preview frames and 39 known-answer photographs, with one existing very faint mark still missed by the source reader. This release has not been tested on a connected phone. See [automatic capture and framing validation](docs/AUTO_CAPTURE_1.1.35.md).

Version 1.1.34 recovers hand-aligned photos whose QR falls below the old header strip. A small QR candidate is independently decoded by the unchanged source reader, and an expanded header is accepted only after the source OMR geometry proves the QR is above the answer region. The latest failed 18.6-second phone photo completed in an 8.8-second saved-photo replay. See [header framing and validation](docs/HEADER_FRAMING_1.1.34.md).

Version 1.1.33 uses fixed targets at the printed square centers and lightweight preview quality/QR checks. Capture no longer waits for paper-edge detection; full-resolution photos still use the unchanged Young Scientist straightening and OMR engines. The 320 ms stability window is separate from shutter and answer-reading time. See [capture latency and validation](docs/CAPTURE_LATENCY_1.1.33.md).

Version 1.1.31 copies the Young Scientist Mobile document scanner, OMR engines and corner editor. Both Android engine libraries in the APK are byte-identical to that app; 869 copied source files are verified unchanged. Roshd integration and current PDF calibration stay outside those engines. The camera now uses that project’s document detection and capture gate. Known limits with yellow and very faint marks remain; 168 of 180 generated Roshd photographs matched all known choices. See [source engine port and validation](docs/YOUNG_SCIENTIST_ENGINE_PORT_1.1.31.md).

Version 1.1.30 recovers faint graphite on bright paper and partial purple strokes using measured local paper contrast. Low-confidence candidates now remain visible per question, require explicit review before signing, and stay neutral on colored sheets. A matched synthetic comparison passed 165/165 cases versus 124/165 in 1.1.29; physical-phone accuracy is not yet measured. See [mark accuracy and review](docs/MARK_ACCURACY_1.1.30.md).

Version 1.1.29 keeps four fixed A5 alignment targets on the camera. Live guidance checks luminance, focus, content stability and QR without searching for paper/fiducials or running a document model. Original photos retain more detail, with locally thresholded printed registration, narrow rail checks and original-resolution corner refinement. A synthetic host comparison passed 180/180 scenes, including 15 shaded scenes rejected previously. See [fixed guides and scanner performance](docs/GUIDED_SCAN_1.1.29.md).

Version 1.1.28 adds read-only downloads of previous academic years and rounds, including inactive years, offline rosters, published manager results and attendance. Each archived original or graded photo can be removed individually, while review records and upload evidence are retained. See [academic-year and photo archives](docs/ACADEMIC_ARCHIVE_1.1.28.md).

Version 1.1.27 adds a 75-question mode using the T1/100 geometry with questions 76-100 faded and excluded from recognition, grading and color marks. A verified roster can also select 75 for imported photos. Fifty random colored examples per 40/75/100 print all passed (150 total). See [75-question mode and random validation](docs/SEVENTY_FIVE_1.1.27.md).

Version 1.1.26 calibrates both supplied November 14 A5 PDFs, their enlarged QR area and the narrower 40-question answer columns. Color-independent ring detection supports ten tested print inks while corner squares and side dots stay black. Printed-grid verification rejects the displaced previous 40-question layout. See [colored prints and QR validation](docs/COLORED_PRINTS_1.1.26.md).

Version 1.1.25 adds the supplied 40-question A5 PDF and a 40/100-question choice before the camera starts. The selected physical family follows each saved photo through background OMR and grading. See [sheet selection and calibration](docs/SHEET_SELECTION_1.1.25.md).

Version 1.1.24 enlarges the corner guides to at least 48 dp, uses local illumination thresholds and narrow rail-strip verification, and preserves camera detail and color. Verified printed geometry uses a 320 ms / four-frame capture gate with modest hand-jitter tolerance and page-centered metering. See [handheld scanner fixes](docs/HANDHELD_SCANNER_1.1.24.md).

The scanner has compact controls and four corner squares. Current T1 and new T3 printed anchors and at least 18 paired timing rows provide direct preview registration before the retained intelligent fallback. Persisted grades display before background color rendering. See [live anchors and scoring](docs/LIVE_ANCHORS_1.1.23.md).

The application uses the official Roshd symbol, application ID `com.haseebullahjan.roshd`, separate encrypted storage and Android Keystore entries, and the Roshd HTTPS origin for login, API calls, file downloads and verified app updates. The single Study Glass theme supports Dari, Pashto, and light/dark appearance.

Features include answer-sheet camera capture, photo/PDF import, T1/T3 native OMR recognition, offline student rosters, answer review, signed submission queues, independent original-image uploads, official result history, and encrypted notices rendered as PDFs. Examination stages 1–12 are supported, with a sole open stage selected automatically and an explicit choice required when several stages are open.

Requirements: JDK 21 for JVM lifecycle tests (Android bytecode targets Java 17), Android SDK 36, NDK 28.2.13676358, CMake 3.22.1. Set `sdk.dir` in local.properties.

```sh
./gradlew :app:assembleRelease -Pabis=arm64-v8a
./gradlew :app:testDebugUnitTest :app:lintDebug -Pabis=arm64-v8a
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_native_project.py
python3 tools/package_source.py output/Roshd-1.1.31-source.zip
```

The release build uses the local development signing identity. Debug installs have the `.nativeqa` suffix. See [Dari instructions](README.fa.md), [architecture](docs/ARCHITECTURE.md), [conversion and verification](docs/ROSHD_MIGRATION.md), and [licenses](THIRD_PARTY_NOTICES.md).

Roshd wire schemas, device proofs and scan/image signing transcripts follow the supplied November 11 API guide. Recognition template IDs, local wrapping-key AAD and encrypted legacy archive magic remain compatibility constants. The native QR router accepts an opaque historical QR only through an unambiguous binding from the verified current-round roster.

The Study folders have a raised left tab and lightweight OMR previews rendered from the actual packaged T1/T3 PDFs. See [folder and API changes](docs/FOLDERS_AND_API_1.1.14.md). Builds use one Gradle worker and a 768 MiB Java heap. Version 1.1.22 was also checked on one existing emulator, without starting another instance.

The **Send all sheets** action sends ready answers in school-scoped batches of up to 50 scans / 4 MiB, then resumes the original-image uploads. Each validated receipt is committed independently; partial failures and interrupted requests retain their original IDs and signed bytes. Per-sheet and per-image-part progress is shown. See [bulk upload](docs/BULK_UPLOAD_1.1.15.md).

Continuous scanning defaults to automatic capture of a stable, sharp, centered page. The camera stays open while a durable, account/edition/round-scoped queue runs OMR on a background worker. Two independent single-photo requests may be in flight; every completion immediately publishes its own provisional score inside the camera. Original JPEG bytes and EXIF reach OpenCV directly; no temporary PNG is required for camera photos. See [continuous scanning and validation](docs/INSTANT_SCORING_1.1.17.md).

With a valid offline grading key, every completed scan also saves a separate colored JPEG: correct selected bubbles receive a green ring/tick, incorrect selections and invalid multiple marks receive red rings/crosses. Blank, uncertain and voided questions remain neutral. Native measured bubble centers align the marks on the normalized image. The original evidence and upload bytes remain intact. Review and archive support zoom, saving and sharing; the live score card opens the colored image. Manual changes invalidate old colors until recalculation. See [colored sheets](docs/COLORED_SHEETS_1.1.18.md).

Version 1.1.19 fixes the first-use/settings theme preview requesting the deleted 60-question artwork. The three decorative folders now reuse the retained 40/100 previews. Android view drawing and startup regression tests run with Robolectric on the JVM, without starting an Android emulator. Existing app data, account keys and scans are retained when updating.

Version 1.1.20 accepts historical four-column roster rows with exact signed QR identities and bounded legacy aliases, including nine-digit aliases. Round information no longer fails on these issued sheets. Unknown historical QR values still need a unique binding from the verified, unexpired current-round roster before the native header routes to either retained family. The original QR, alias, student identity and signed bytes are preserved. See [round-information compatibility](docs/ROUND_INFORMATION_1.1.20.md).

Version 1.1.21 replaces the active 100-question sheet and its preview with the supplied corrected A5 PDF. A new stateless square-anchor/paired-row rectifier replaces the old boundary/dewarp models. All four squares and at least 18 paired rows must match; missing/ambiguous registration fails safely. QR identity remains required (the supplied blank PDF contains only a placeholder). OMR, T3/40, original evidence, scoring, colors and bulk upload are retained. See [new sheet and rectifier](docs/SHEET_RECTIFIER_1.1.21.md).

Version 1.1.22 restores the original intelligent document scanner, its MNN models, live tracking and manual corner editor, as requested. The corrected 100-question PDF from 1.1.21, its OMR calibration and the unchanged 40-question family are retained. See [scanner restoration](docs/SCANNER_RESTORED_1.1.22.md).
# roshombile
