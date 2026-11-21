# 40/100-question scanner selection — 1.1.25

Entering the scanner presents two paper previews and a choice of 40 or 100 questions. The camera and analysis start only after a choice. The selected count is shown in the camera header, survives activity recreation, and is persisted as `captureQuestionCount` with each original JPEG. Returning to the scanner starts a fresh choice.

The supplied `roshd-40q-A5.pdf` is packaged unchanged. Its actual print layout is calibrated as `t3-a5-square-20261113`: four 7 mm corner squares, twenty paired timing dots, and two columns of twenty questions. Question 1 is in the right column; options 1–4 run right to left. The preview in both the chooser and folders is rendered from the PDF. The supplied PDF contains a QR placeholder; a valid issued student QR and current exam package are still needed for grading.

The existing T3 template ID and older T3 geometries remain compatible. The new geometry has its own header QR region and twenty-row registration. Geometry variants retain the parent family's credential rules, confidence thresholds and question identities. The 100-question calibration and model assets are unchanged; no 60-question template is introduced.

Live detection uses the selected family's anchored layout. Full recognition verifies squares and paired rows, decodes identity only in the header, then reuses that measured geometry for answer reading. Selected camera jobs restrict the native template list; mixed/recovered batches also check each record's selection before attaching answers or a score. Selection never substitutes for QR identity.

QA fixtures are rendered from the packaged PDF and filled using the supplier's independent millimetre map (`qa/40q-supplied-map-1.1.25.json`). They cover all four options, every question, all rotations, perspective, blank paper, missing/mismatched QR, wrong family selection, missing squares, missing timing rows, and preview shadow. Instrumentation also exercises the unchanged 100/legacy-40 fixtures and the saved-photo queue, computed grade and red/green annotation. See [validation](../qa/VALIDATION.md) for measured results and limits.

Reproduce fixtures with `python3 tools/generate_40q_fixtures.py`. Android test suites are `sheet-40`, `scanner-restore`, and `scanner` with `-e count 40` when the virtual camera is showing the new paper. Fixture data is packaged only in the test APK.
