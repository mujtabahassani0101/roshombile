# Registered ink verification — 1.1.38 (4224)

This release improves faint and colored handwriting recognition without modifying the copied Young Scientist engines. Study Glass, archived photographs, deletion, login and server protocols remain in place. No phone was connected; validation below uses synthetic photographs on the computer.

## Processing

`InkVerification.cpp` runs after the source engine has independently decoded the header and proved all 20/25 registration rows, a finite reprojection error no greater than 1.5 pixels, and a non-fallback homography. It reuses the source-measured bubble centers and refinement safety statistics. It never creates another warp or changes the decoded identity.

The unchanged source `AnswerReader` reads three representations of the registered sheet: minimum color channel, modest color contrast, and minimum-channel contrast. This retains visible yellow/colored strokes which the luminance reading can miss. It adds no QR search, document model, geometric interpolation, or full-sheet recognition retry.

Two strong readings must agree before a previously blank choice can become a recovered candidate. Competing readings and multiple broad marks block recovery. Contrast must also exist in the original registered image: either the source's measured paper-relative ink darkness is sufficient, or local samples inside the circle independently show dark/chromatic ink relative to surviving paper. Channel ratios account for local color casts. Enhanced pixels alone cannot authorize an answer. A light erasure in shade exposed an overly permissive initial condition; the final condition leaves those answers blank.

**Every recovered answer requires explicit confirmation.** Conflicting, multiple-mark and faint residual evidence also requires review. The UI explains the reason for each affected question. Existing signing checks block unconfirmed answers, and colored sheets leave them neutral. Confirmation metadata remains local and is excluded from signed answer observations. Successful original selected choices are retained; verification never silently replaces one with a different selection.

Representations exist only in memory; the archived original and the source-normalized annotation image are unchanged. Temporary representations are released between passes. Missing normalized images or verification failures retain the original source reading. A failed verification cannot partially overwrite it. Questions 76–100 remain excluded for the 75-question mode.

## Measured comparison

The baseline is the 1.1.37 application adapter. Both versions use the same protected source engines, templates, preparation, and input files. Expected choices come from seeded RNG manifests and supplier coordinates, independently of recognition. Input hashes are checked before and after every scene.

| Corpus | Images | Question checks | Baseline errors | New errors | Baseline unflagged errors | New unflagged errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Existing color/mark/lighting fixtures | 345 | 24,725 | 1,827 | 430 | 215 | 0 |
| Separate blank/erasure/partial-mark fixtures | 96 | 6,880 | 1,036 | 213 | 182 | 0 |
| Additional seed, including warm color casts | 120 | 8,600 | 1,111 | 240 | 189 | 0 |
| Total | 561 | 40,205 | 3,974 | 883 | 586 | 0 |

An error means a single-choice/blank mismatch, or a multiple-mark question that escaped review. Multi-mark cases are counted as acceptable only when classified multiple or flagged for confirmation; this does not mean a single choice was proven correct. Among the 519 single-choice/blank scenes alone, mismatches fall from 3,972 to 883 across 37,195 answers. All remaining mismatches in this corpus require review; they are not counted as correct.

- 3,089 previously blank choices become source-reader candidates requiring confirmation.
- No previously correct answer regresses, and no known blank becomes a selected choice.
- All 561 images retain complete native registration and the expected QR identity.
- The 180 existing randomized colored/lighting photographs match all 12,900 known marks, versus 64 missed marks previously. Recovered choices still require confirmation.
- Very faint, narrow purple strokes and some blurred graphite remain unresolved. This is not a claim of perfect automatic reading or physical-phone accuracy.

## Other validation

- Full JVM suite: 255 tests, 248 passed, 7 opt-in live server tests skipped; no failures/errors. A new regression verifies that a high-confidence recovered candidate cannot bypass explicit confirmation or leak local metadata into signed observations.
- Native contract checks use strict JSON parsing, preserve existing selected choices/QR/annotation geometry, reject count/family mismatch, preserve the original reading without a normalized image, trim inactive 75-question cells, and require multiple-mark review.
- Camera-framing replay covers 39 known-answer photographs plus six negative registrations, EXIF-6 orientation and a QR placed in the answer body. Printed pixels and archived input hashes remain protected.
- Source audit verifies all 869 copied source files and both packaged ARM64 engine binaries remain exact. Android QA sources compile; no instrumentation or physical-camera run is claimed.
- ARM64 APK retains package `com.haseebullahjan.roshd` and the previous local development signing certificate. Version is `1.1.38` / `4224`; local storage schema is unchanged.

Reports, comparison manifests, original test attempts, JVM XML and APK metadata are in `output/accuracy-1.1.38/`. `comparison.json` defines the measurements; `final/report.json` contains the final 561 scene results. New scans use the change immediately. Existing unsigned sheets can use the existing **خوانش دوباره** action; previously signed requests are preserved.

The replay tools are `tools/check_read_accuracy.py`, `tools/generate_ink_holdout.py`, `tools/check_ink_contract.py`, and the existing camera preparation regression tool. Host builds use `output/source-port-1.1.31/build/libroshd_engine_adapter.so`; the deployed Android engines remain the original source application's binaries.
