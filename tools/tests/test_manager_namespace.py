"""Check Manager package, JNI and component identities as one contract."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "manager/app/src"
PACKAGE = "io.milksu.manager"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class ManagerNamespaceTests(unittest.TestCase):
    def test_source_packages_match_their_directories(self):
        checked = 0
        for source_set, language in (("main", "java"), ("main", "aidl"), ("test", "java")):
            base = SOURCE / source_set / language
            for path in base.rglob("*"):
                if path.suffix not in (".kt", ".java", ".aidl"):
                    continue
                with self.subTest(path=path.relative_to(ROOT)):
                    declaration = re.search(
                        r"^package\s+([\w.]+)", path.read_text(encoding="utf-8"), re.M
                    )
                    self.assertIsNotNone(declaration)
                    expected = ".".join(path.relative_to(base).parent.parts)
                    self.assertEqual(declaration.group(1), expected)
                    self.assertTrue(expected == PACKAGE or expected.startswith(PACKAGE + "."))
                    checked += 1
        self.assertGreater(checked, 250)

    def test_jni_exports_match_all_kotlin_native_members(self):
        kotlin = (SOURCE / "main/java/io/milksu/manager/NativeBridge.kt").read_text(encoding="utf-8")
        expected = set(re.findall(r"external fun\s+(\w+)", kotlin))
        for name in re.findall(r"val\s+(\w+):[^\n]+external get", kotlin):
            expected.add(name if name.startswith("is") else "get" + name[0].upper() + name[1:])
        native = (SOURCE / "main/cpp/jni.cc").read_text(encoding="utf-8")
        exported = set(re.findall(r"Java_io_milksu_manager_NativeBridge_(\w+)\(", native))
        self.assertEqual(expected, exported)
        self.assertEqual(native.count('FindClass("io/milksu/manager/Natives$Profile")'), 2)
        preload = "Java_io_milksu_manager_magica_AppZygotePreload_forkDontCareAndExecKsud"
        self.assertIn(preload, native)

    def test_manifest_components_and_daemon_launch_resolve_to_milksu_classes(self):
        tree = ET.parse(SOURCE / "main/AndroidManifest.xml")
        for node in tree.iter():
            if node.tag not in ("application", "activity", "service", "receiver"):
                continue
            for attribute in ("name", "zygotePreloadName"):
                name = node.get(ANDROID + attribute)
                if not name:
                    continue
                name = PACKAGE + name if name.startswith(".") else name
                relative = name.replace(".", "/")
                self.assertTrue(any((SOURCE / "main/java" / (relative + suffix)).is_file()
                                    for suffix in (".kt", ".java")), name)
        expected = "io.milksu.manager.ui.MainActivity"
        self.assertIn(expected, (ROOT / "userspace/ksud/src/late_load.rs").read_text(encoding="utf-8"))
        self.assertIn(expected, (ROOT / "tools/capture-manager-transition.py").read_text(encoding="utf-8"))
        self.assertIn('namespace = "io.milksu.manager"',
                      (ROOT / "manager/app/build.gradle.kts").read_text(encoding="utf-8"))
        receiver = SOURCE / "main/java/io/milksu/manager/magica/BootCompletedReceiver.java"
        self.assertIn('"io.milksu.manager.magica.LAUNCH"', receiver.read_text(encoding="utf-8"))

    def test_production_sources_have_no_upstream_package_encodings(self):
        for base in (SOURCE / "main", ROOT / "userspace/ksud/src", ROOT / "manager/tools"):
            for path in base.rglob("*"):
                if path.suffix not in (".kt", ".java", ".aidl", ".cc", ".h", ".xml", ".rs", ".ps1"):
                    continue
                with self.subTest(path=path.relative_to(ROOT)):
                    source = path.read_text(encoding="utf-8")
                    for old in ("me.weishu.kernelsu", "me/weishu/kernelsu", "Java_me_weishu_kernelsu_"):
                        self.assertNotIn(old, source)
