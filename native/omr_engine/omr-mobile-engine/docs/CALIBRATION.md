# A5 T1/T2/T3 answer-sheet calibration

The active production T1 template was extracted directly from the newest
supplied PDF:

```text
A5 - 1 (Copy 2) (1).pdf
SHA-256: 50dadda000a5012bba3c3bc4eec1b86f70fc919426f5afb68561d8af230c0fac
```

## Reference geometry

- PDF: one A5 page, `420 × 595 pt`.
- canonical render: `300 DPI`, `1750 × 2480 px`.
- sheet type: uppercase `T1`.
- questions: 100, arranged in four blocks of 25 rows.
- choices: four per question, displayed left-to-right as `4,3,2,1`.
- answer bubbles: 400 total.
- timing marks: 29 per side: four header rows and 25 answer rows.
- QR: inside the combined top header panel.
- student-code bubble grid: absent from this revision.

The exact portable coordinates are stored in
`templates/yse_omr_a5_t1_100q_v2.json`. Coordinates were taken from the PDF's
vector objects and scaled to the 300-DPI canonical render. They were not
estimated from a screenshot.

Student-code recognition remains part of the reusable engine, but it is disabled
for this physical template. T1 v2 therefore returns `studentCode.available=false`
and does not add a `student_code_incomplete` review reason.

## T2 and T3 geometry

The 60-question `T2` layout comes from `60.pdf` (SHA-256
`66302f97baab978f77ca9d23465b8346c7d61012f857220d1b3e90984e4121f7`).
It contains three 20-row blocks ordered left-to-right as questions 41–60,
21–40, and 1–20, for 240 answer bubbles total.

The 40-question `T3` layout comes from `40.pdf` (SHA-256
`6b3abb92214e66d6885f54291f4b70be4d29c3faa4e198a2c01b6117b2b2c142`).
It contains two 20-row blocks ordered left-to-right as questions 21–40 and
1–20, for 160 answer bubbles total.

Both are A5 at the same 300-DPI canonical size, have 24 timing marks per side
(four header rows plus 20 answer rows), use 14-pt vector circles, and have no
student-code bubble grid. Their exact coordinates are stored in
`templates/yse_omr_a5_t2_60q_v1.json` and
`templates/yse_omr_a5_t3_40q_v1.json`.

## QR production requirement

ZXing decodes the QR in the supplied sample PDF as:

```text
S2XMHKMQ7J33Y0NDB1XN
```

That payload does not satisfy the production routing contract because it does
not begin with uppercase `T1`. Before production printing, the QR generator must
emit a payload beginning with `T1`, for example:

```text
T1:S2XMHKMQ7J33Y0NDB1XN
```

The supplied T2 and T3 sample PDFs contain the same non-production payload,
`S2XMHKMQ7J33Y0NDB1XN`. Their production QR values must instead begin with
uppercase `T2` and `T3`. The engine can receive all three templates together,
reads this prefix first, and selects the matching physical geometry before it
classifies any answer.

## Accuracy evidence

- the PDF contains exactly 58 timing dots: 29 on each side;
- the calibrated template contains exactly 29 timing rows and 400 answer
  bubbles;
- all 224 directly extractable answer-circle vector centers match their template
  centers within `0.003 px` at the 300-DPI reference scale;
- all T2/T3 answer and rail-circle centers match the supplied vector PDFs within
  `0.001 px` at the 300-DPI reference scale;
- the QR decodes successfully when cropped using the stored template ROI;
- regression tests verify the A5 page size, timing rows, answer blocks and
  bubble totals for T1/T2/T3, the absence of student-code grids, QR bounds, and
  uppercase routing;
- side-rail homography, orientation correction, bubble-grid refinement,
  shadow-resistant ink scoring, multi-mark detection, debug images, compact
  output, and review gates remain enabled.

The supplied PDFs are authoritative geometry references but are not valid
production fixtures because their QR values lack the required `T1`, `T2`, or
`T3` prefixes. Final release must also be tested with real phone captures of
regenerated sheets covering blank, single-mark, multi-mark, faint-pencil,
blue-pen, skewed, shadowed, and partially cropped cases.
