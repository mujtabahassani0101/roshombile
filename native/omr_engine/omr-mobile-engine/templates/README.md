# Production template catalog

- `yse_omr_a5_t1_100q_v2.json`: current changed A5 T1 sheet, 100
  questions, 29 timing dots per side, combined QR/header panel, and no
  student-code bubble grid.
- `yse_omr_a5_t2_60q_v1.json`: A5 T2 sheet, 60 questions in three
  20-row blocks, 24 timing dots per side, and no student-code bubble grid.
- `yse_omr_a5_t3_40q_v1.json`: A5 T3 sheet, 40 questions in two
  20-row blocks, 24 timing dots per side, and no student-code bubble grid.
  Its primary geometry is retained. The `geometryVariants` array adds the
  `t3-2x20-2026` print: 25px bubbles, answer rows 706.354..2372.813px and the
  same T3 QR binding. These are full calibrated geometry objects; identity,
  QR policy, read thresholds and live options are inherited from the parent.

`QrSheetTypeResolver` binds complete uppercase `T1:code:S2identifier`,
`T2:code:S2identifier`, and `T3:code:S2identifier` values to these exact
physical templates. In multi-template mode the engine reads the QR first and
selects the bound template before answer classification.

These are the supported physical answer-sheet families in this package.
