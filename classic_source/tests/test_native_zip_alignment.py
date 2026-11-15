"""Real SDK ZIP alignment on synthetic payloads; no OEM, signing or devices."""
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile


def zipalign_tool():
    found = shutil.which('zipalign')
    if found:
        return found
    for value in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
                  '/usr/local/lib/android/sdk'):
        if value:
            tools = Path(value) / 'build-tools'
            for path in sorted(tools.glob('*/zipalign'), reverse=True):
                if path.is_file():
                    return str(path)
    return None


class NativeZipAlignment(unittest.TestCase):
    def test_real_sdk_aligns_16k_and_preserves_every_payload(self):
        tool = zipalign_tool()
        if not tool:
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                self.fail('Hosted source CI must execute the real SDK alignment regression')
            self.skipTest('Android SDK zipalign unavailable; synthetic parser tests still run')
        with tempfile.TemporaryDirectory() as d:
            source, output = Path(d) / 'fixture.apk', Path(d) / 'aligned.apk'
            with zipfile.ZipFile(source, 'w') as z:
                z.writestr('AndroidManifest.xml', b'fixture-only')
                z.writestr('classes.dex', b'fixture-bytecode-only')
                z.writestr('lib/x86_64/libsynthetic.so', b'fixture-native-payload' * 200)
                z.writestr('assets/fixture', b'preserve-asset')
            subprocess.run([tool, '-f', '-P', '16', '4', str(source), str(output)],
                           check=True, capture_output=True, timeout=30)
            subprocess.run([tool, '-c', '-P', '16', '4', str(output)],
                           check=True, capture_output=True, timeout=30)
            with zipfile.ZipFile(source) as a, zipfile.ZipFile(output) as b:
                self.assertEqual(a.namelist(), b.namelist())
                for name in a.namelist():
                    self.assertEqual(a.read(name), b.read(name))
                # Independently inspect the LOCAL header, not the central
                # directory extra field that zipalign intentionally leaves alone.
                member = b.getinfo('lib/x86_64/libsynthetic.so')
                with output.open('rb') as f:
                    f.seek(member.header_offset)
                    header = f.read(30)
                name_len, extra_len = struct.unpack_from('<HH', header, 26)
                self.assertEqual((member.header_offset + 30 + name_len + extra_len) % 16384, 0)
            # Reproduce the old 4 KiB route and prove it does not qualify.
            old = Path(d) / 'four-k.apk'
            subprocess.run([tool, '-f', '-p', '4', str(source), str(old)],
                           check=True, capture_output=True, timeout=30)
            checked = subprocess.run([tool, '-c', '-P', '16', '4', str(old)],
                                     capture_output=True, timeout=30)
            self.assertNotEqual(checked.returncode, 0)
