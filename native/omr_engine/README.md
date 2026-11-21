# Young Scientist native OMR engine

The Android application calls the retained r26 C ABI through its Kotlin `OmrNative` object and `app/src/main/cpp/OmrJni.cpp`. The optional upstream example JNI adapter is disabled. Recognition runs on bounded application background workers; the controller stage queue only schedules and commits records. The unchanged native engine retains one persistent header worker and independent T1, T2 and T3 answer queues.

| Exact printed QR family | Template asset | Capacity |
| --- | --- | --- |
| T1 | yse_omr_a5_t1_100q_v2.json | 100 |
| T2 | yse_omr_a5_t2_60q_v1.json | 60 |
| T3 | yse_omr_a5_t3_40q_v1.json | 40 |

Kotlin installs the three bundled assets into private storage and sends `templateJsonPaths`. The engine validates the complete printed QR, selects the physical layout and returns one numbered record per input, including failures. Kotlin requires an exact valid signed-roster match and complete observations before marking a scan ready. Original evidence and its SHA-256 remain separate from recognition copies. Android JSON escaped template paths are decoded by the string-array reader.

`HeaderEngine`, `T1AnswerEngine`, `T2AnswerEngine` and `T3AnswerEngine` retain their calibration and r26 highest-fill answer policy. The engine revision is `omr-mobile-api-v2-four-engines-r26-highest-fill`, schema/C ABI version 2. OpenCV and ZXing-C++ remain native dependencies. See [API](omr-mobile-engine/docs/API.md), [four-engine design](docs/FOUR_ENGINES.fa.md) and [calibration history](docs/REVISION_HISTORY.md).

From the project root:

```sh
./gradlew :app:assembleRelease -Pabis=arm64-v8a
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -Pabis=x86_64
```

The Android instrumentation suite exercises photo import through Kotlin/JNI using deterministic 100-, 60- and 40-question sheets, including rotated inputs and all 200 known choices. Fixtures and their generator are test-only. See [validation](../../qa/VALIDATION.md) for commands, results and the physical-camera verification boundary.

The application packages the active `omr-mobile-engine` implementation. The older `omr-live-detector` and top-level templates are retained native reference material and are not linked as the recognition engine.
