import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_native_project import audit_native_project

class NativeProjectAuditTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        for name in ["gradlew", "settings.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml"]:
            path = self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text("")
    def test_legacy_storage_compatibility_is_not_a_flutter_dependency(self):
        (self.root/"app/Storage.kt").write_text('val preferences = "FlutterSecureStorage"; val marker = "flutter-v2"')
        self.assertEqual(1,audit_native_project(self.root)["kotlinFiles"])
    def test_dart_sources_and_pubspec_are_rejected(self):
        for name in ["main.dart", "pubspec.yaml", ".flutter-plugins-dependencies"]:
            with self.subTest(name=name):
                path=self.root/name;path.write_text("")
                with self.assertRaises(ValueError):audit_native_project(self.root)
                path.unlink()
    def test_hidden_dart_cache_is_rejected(self):
        (self.root/".dart_tool").mkdir()
        with self.assertRaises(ValueError):audit_native_project(self.root)
    def test_flutter_plugin_is_rejected(self):
        (self.root/"app/build.gradle.kts").write_text('plugins { id("dev.flutter.flutter-gradle-plugin") }')
        with self.assertRaises(ValueError):audit_native_project(self.root)
    def test_archived_flutter_is_rejected(self):
        for member in ["lib/arm64-v8a/libflutter.so", "assets/flutter_assets/AssetManifest.bin", "source/lib/main.dart"]:
            with self.subTest(member=member):
                with zipfile.ZipFile(self.root/"app.apk","w") as archive:archive.writestr(member,b"fixture")
                with self.assertRaises(ValueError):audit_native_project(self.root)

if __name__ == "__main__":unittest.main()
