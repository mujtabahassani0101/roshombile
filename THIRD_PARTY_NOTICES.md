# Included third-party material

This migration retains the supplied project's native recognition sources, binaries, models, fonts, icons and branded image assets. It does not change their ownership or grant a new license over them.

- OMR core / live detector: licenses in `native/omr_engine/omr-mobile-engine/LICENSE` and `native/omr_engine/omr-live-detector/LICENSE`.
- ZXing: `native/omr_engine/third_party/zxing-cpp/LICENSE`.
- OpenCV: `native/omr_engine/third_party/opencv/LICENSE` and the Maven AAR's notices.
- Document scanner and OMR sources copied from the local Young Scientist Mobile project: `native/document_scanner` and `native/omr_engine`. Original Android engine binaries and their SHA-256 provenance are retained under `native/roshd_adapter/vendor`. The Ama copy additionally includes Moji Scan 3.0.2 / Paperframe scanner sources, models and ARM64 MNN runtime, recorded in `docs/MOJI_SCANNER_PROVENANCE.json`.
- Vazirmatn notice fonts: `app/src/main/assets/notice_fonts/OFL.txt`.
- Phosphor icon font: `app/src/main/assets/fonts/Phosphor-LICENSE.txt`.
- Feather icons: `app/src/main/assets/icons/feather/LICENSE`.
- AndroidX, Google ML Kit and OpenCV runtime dependencies remain Maven dependencies under their respective upstream licenses.

The UI and application architecture are independent Kotlin implementations informed by the local Telegram reference described in `docs/ARCHITECTURE.md`. Telegram's Java messaging implementation and its backend are not bundled here.

- Visual design guidance: [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill), style IDs `pixel-art` and `organic-biophilic`. The search scripts and repository are reference-only and are not bundled into the application.
- AndroidLiquidGlass / Backdrop 1.0.6 by Kyant: [upstream](https://github.com/Kyant0/AndroidLiquidGlass), Apache 2.0; official Maven artifact `io.github.kyant0:backdrop:1.0.6`. License: `licenses/AndroidLiquidGlass-APACHE-2.0.txt`, also shipped in the APK at `assets/licenses/AndroidLiquidGlass-APACHE-2.0.txt`. The app uses its layer recording, glass surface, highlight and native blur/lens effects.
- Study Glass artwork: the generated transparent illustration (`study-frog.png`) created for this project. The supplied Victoria Grinevich reference guides composition and palette; its screenshot is not shipped inside the application.


- Roshd identity: official public symbol from [roshd.azmoonic.com](https://roshd.azmoonic.com/roshd-symbol.png), used in Android launcher/splash resources and application headers.

- OMR folder previews are generated from the bundled T1/T2/T3 coordinate templates by `tools/generate_folder_omr_assets.py`. They contain no pupil data, marked answers or valid examination QR credentials.

- Moji Scan / Paperframe: copied scanner engine, geometry, tracking, JNI, model weights and MNN runtime. Original notices and source provenance are retained in `docs/moji/`; MNN license is in `app/src/main/assets/scanner/MNN-LICENSE.txt`. The supplied model weights do not acquire a public redistribution license through this copy.
