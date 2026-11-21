# Scanned-sheet deletion — 1.1.36 (4222)

History (`نتیجه‌های اسکن`) and the sheet review page now include `حذف برگهٔ اسکن‌شده`. The confirmation explains that this removes the local sheet, answers, associated app photos and outgoing queue entry. It does not request deletion of any server result. Cancel leaves the sheet intact. Deletion from review returns to the previous page.

The scoped SQLite transaction removes the scan record, camera job, original/colored archive entries and archive removal markers. It removes the corresponding answer outbox packet and image-upload state unless another surviving local record shares that packet. Sealed and delivered local sheets can also be deleted; no signed payload is rewritten and no network operation is issued.

Pending and currently recognized camera sheets can be deleted. Native recognition may finish, but persistence checks that the record still exists before writing any result. Its dedicated working directory is discarded after the worker ends. Late colored-image callbacks likewise discard their files instead of recreating the sheet or archive entry.

A small encrypted `scan-trash` journal records owned file paths for asynchronous cleanup. Cleanup waits for active recognition and previously queued color work, then removes unreferenced source, normalized, colored and restored files. Previous colored versions named for the exact sheet are also collected. Shared files and files outside the account's private media directory are retained. An unfinished cleanup resumes during account recovery after restart. The original/colored archive-only deletion feature keeps its existing behavior.

The brief initial `capturing` phase must finish before deletion, and deletion is rejected during an active send/import/edit operation. A camera deletion event clears the matching live feedback and duplicate lock so the paper can be scanned again.

Validation includes actual encrypted SQLite deletion, account isolation, outbox/camera-job removal, preserving other/shared/external files, late native output, cleanup ordering with color workers, late colored output, restart recovery, cancellation and controls on both screens, and rescanning a deleted paper. Existing archive, sending, review, colored-sheet, preview and activity regression tests are included in the release check. No phone connection or live-device installation is performed for this release.

Build and test logs: `output/scan-deletion-1.1.36/`.
APK: `output/roshd-1.1.36-arm64.apk`.

Release validation: 83 tests passed, including 7 dedicated deletion tests; no failures or skips. APK signature and ARM64 version metadata verified. Source audit confirms 869 unchanged copied files and both exact original engine libraries in the APK.
