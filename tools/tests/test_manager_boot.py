"""Exercise the production boot callbacks without packages.list events."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("cc"), "requires a host C compiler")
class ManagerBootTests(unittest.TestCase):
    def test_existing_manager_is_scanned_at_startup_and_boot_completed(self):
        headers = (
            "feature/selinux_hide.h", "feature/avc_spoof.h",
            "linux/err.h", "linux/fs.h", "linux/namei.h", "linux/printk.h",
            "policy/allowlist.h", "policy/feature.h", "klog.h",
            "runtime/ksud_boot.h", "runtime/ksud.h",
            "manager/manager_observer.h", "manager/throne_tracker.h",
            "milksu_root.h",
        )
        with tempfile.TemporaryDirectory(prefix="milksu-manager-boot-") as temporary:
            directory = Path(temporary)
            for name in headers:
                header = directory / name
                header.parent.mkdir(parents=True, exist_ok=True)
                header.touch()
            binary = directory / "manager-boot"
            subprocess.run([
                "cc", "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                "-Wno-unused-parameter", "-I" + str(directory),
                str(ROOT / "tools/tests/native_manager_boot.c"),
                "-o", str(binary),
            ], check=True, capture_output=True, text=True, timeout=60)
            subprocess.run([str(binary)], check=True, capture_output=True, timeout=10)
