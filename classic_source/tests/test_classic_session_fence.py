"""Pure-JVM regression for the production Classic session generation fence.

No emulator, Android SDK, private APK, network or device access.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FENCE = ROOT / "bridge/src/io/github/lxsdd/yeelightclassicdj/ui/ClassicSessionFence.java"
HARNESS = r'''
package io.github.lxsdd.yeelightclassicdj.ui;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class ClassicSessionFenceHarness {
    static void need(boolean condition) {
        if (!condition) throw new AssertionError("session fence violated");
    }
    public static void main(String[] args) throws Exception {
        ClassicSessionFence fence = new ClassicSessionFence();
        long first = fence.begin();
        need(first > 0L && fence.owns(first));
        need(fence.begin() == 0L);
        need(!fence.cancel(first + 1L));
        need(fence.owns(first));
        need(fence.cancel(first));
        need(!fence.owns(first));
        need(!fence.cancel(first));
        long second = fence.begin();
        need(second > first && fence.owns(second) && !fence.owns(first));
        need(fence.cancel() == second);
        need(fence.cancel() == 0L);
        need(!fence.owns(second));
        final ClassicSessionFence contended = new ClassicSessionFence();
        final int workers = 16;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(workers);
        final AtomicInteger admitted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    if (contended.begin() != 0L) admitted.incrementAndGet();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                } finally {
                    finish.countDown();
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        finish.await();
        for (Thread t : threads) t.join();
        need(admitted.get() == 1);
        long generation = contended.cancel();
        need(generation > 0L && !contended.owns(generation));
        need(contended.begin() > generation);
        System.out.println("PASS ClassicSessionFence: unique epochs, stale callbacks, stop idempotence, concurrent begin");
    }
}
'''


class ClassicSessionFenceTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "Java toolchain unavailable")
    def test_real_service_fence_is_concurrency_safe(self):
        self.assertTrue(FENCE.is_file())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "io/github/lxsdd/yeelightclassicdj/ui/ClassicSessionFenceHarness.java"
            target.parent.mkdir(parents=True)
            target.write_text(HARNESS, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "--release", "8", "-d", str(root / "classes"),
                 str(FENCE), str(target)],
                text=True, capture_output=True, timeout=35)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            executed = subprocess.run(
                ["java", "-cp", str(root / "classes"),
                 "io.github.lxsdd.yeelightclassicdj.ui.ClassicSessionFenceHarness"],
                text=True, capture_output=True, timeout=35)
            self.assertEqual(executed.returncode, 0, executed.stderr)
            self.assertIn("PASS ClassicSessionFence", executed.stdout)


if __name__ == "__main__":
    unittest.main()
