# Study Glass consolidation — 1.1.37 (4223)

Every application screen now uses Study Glass. Organic palettes, native theme resources, alternate headers and dock, first-use chooser, settings picker, preview code and its unused thin icon font have been removed. The shared Roshd logo and rounded shapes now live in `RoshdBrand.kt` and remain available to Study components.

The obsolete `visualStyle` preference is no longer read. A previous Organic or unknown selection therefore opens Study automatically without changing saved accounts, scans, queues, language or night mode. Saved navigation containing the old chooser is discarded in favor of login or Home; valid login/settings and authenticated back stacks still restore. Light/dark appearance and Dari/Pashto remain supported.

Exam search from the previous alternate layout is retained in Study cards, together with offline-package status, question counts, pagination and saved search state. Camera, import, review, individual archive-photo deletion and entire scan deletion remain accessible through the one visual system. No recognition or straightening algorithm changed in this release.

Validation:

- ARM64 release APK assembled successfully; Android instrumentation QA sources compile against the single-theme API. Instrumentation tests were not run on a phone.
- Full JVM suite: 254 tests, 247 passed, 7 opt-in live API tests skipped, no failures or errors. The five new regression tests cover first use, prior preferences in light/dark Pashto, retained account and scan data, old chooser navigation, valid back-stack restoration, and exam search/pagination.
- APK inspection confirms obsolete Organic/chooser classes, Organic style resources and thin icon font are absent; the official logo remains packaged.
- Source-engine audit: all 869 protected source files and both ARM64 engine binaries still match the Young Scientist source port exactly.
- Package `com.haseebullahjan.roshd`, version `1.1.37` / `4223`, signed with the same local development certificate as the previous release.

Deliverable: `output/roshd-1.1.37-arm64.apk`. Build log, preserved JVM reports, source audit, change patch and APK hashes are in `output/single-theme-1.1.37/`.
