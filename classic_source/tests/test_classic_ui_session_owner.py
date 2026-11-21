"""Executable Java ownership-generation tests for real Classic service gate.

Synthetic JVM only; no Android device, OEM APK or lamp access.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicUiSessionOwner.java"
HARNESS = r"""
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public final class UiSessionOwnerHarness {
    static void need(boolean valid) { if (!valid) throw new AssertionError("ui owner fence"); }
    public static void main(String[] args) throws Exception {
        ClassicUiSessionOwner gate = new ClassicUiSessionOwner();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        need(!ClassicUiSessionOwner.valid(null));
        need(!ClassicUiSessionOwner.valid(""));
        need(!ClassicUiSessionOwner.valid("forged"));
        need(!ClassicUiSessionOwner.valid(first.toUpperCase(java.util.Locale.ROOT)));
        need(!gate.owns(first) && gate.current() == null);
        need(!gate.begin(null) && !gate.begin("foreign"));
        need(gate.begin(first));
        need(gate.owns(first) && !gate.owns(second) && !gate.owns(null));
        need(!gate.begin(second) && first.equals(gate.current()));
        gate.clear();
        need(!gate.owns(first) && gate.current() == null);
        need(gate.begin(second) && gate.owns(second) && !gate.owns(first));
        gate.clear();

        final ClassicUiSessionOwner concurrent = new ClassicUiSessionOwner();
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(12);
        final AtomicInteger admitted = new AtomicInteger();
        for (int i=0; i<12; i++) {
            new Thread(() -> {
                try {
                    ready.await();
                    if (concurrent.begin(UUID.randomUUID().toString())) admitted.incrementAndGet();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                } finally { done.countDown(); }
            }).start();
        }
        ready.countDown();
        done.await();
        need(admitted.get() == 1);
        need(concurrent.current() != null);
        System.out.println("PASS ClassicUiSessionOwner: stale/missing/foreign/duplicate/concurrent");
    }
}
"""

class UiSessionOwnerTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java toolchain not installed")
    def test_actual_production_ui_owner_concurrent_generation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            harness = root / "UiSessionOwnerHarness.java"
            harness.write_text(HARNESS, encoding="utf-8")
            result = subprocess.run(
                ["javac", "--release", "8", "-d", str(root / "classes"),
                 str(SOURCE), str(harness)],
                capture_output=True, text=True, timeout=35)
            self.assertEqual(result.returncode, 0, result.stderr)
            executed = subprocess.run(
                ["java", "-cp", str(root / "classes"),
                 "io.github.lxsdd.yeelightclassicdj.ui.UiSessionOwnerHarness"],
                capture_output=True, text=True, timeout=35)
            self.assertEqual(executed.returncode, 0, executed.stderr)
            self.assertIn("PASS ClassicUiSessionOwner", executed.stdout)

if __name__ == "__main__":
    unittest.main()
