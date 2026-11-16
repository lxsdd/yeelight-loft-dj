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

    def test_readonly_elf_load_census_distinguishes_zip_from_elf_alignment(self):
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from private_payload_parity import (
            _elf_load_alignment, native_elf_16k_census)
        def elf64(p_align, offset=0, vaddr=0):
            blob = bytearray(120)
            blob[:16] = b'\x7fELF' + bytes([2,1,1]) + bytes(9)
            struct.pack_into('<Q', blob, 32, 64)
            struct.pack_into('<HH', blob, 54, 56, 1)
            struct.pack_into('<IIQQQQQQ', blob, 64,
                             1, 5, offset, vaddr, 0, 1, 1, p_align)
            return bytes(blob)
        self.assertEqual(_elf_load_alignment(elf64(16384)), 'LOAD_16K')
        self.assertEqual(_elf_load_alignment(elf64(4096)), 'LOAD_NOT_16K')
        self.assertEqual(_elf_load_alignment(elf64(16384, 4096, 0)), 'LOAD_NOT_16K')
        self.assertEqual(_elf_load_alignment(b'not an ELF'), 'UNVERIFIED')
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / 'synthetic-native.apk'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('lib/x86_64/one.so', elf64(4096))
                z.writestr('lib/arm64-v8a/two.so', elf64(16384))
                z.writestr('assets/fake.so', b'not a library')
            with zipfile.ZipFile(archive) as z:
                census = native_elf_16k_census(z)
        self.assertEqual(census['status'], 'NATIVE_ELF_LOAD_16K_INCOMPATIBLE')
        self.assertEqual(census['native_library_count'], 2)
        self.assertEqual(census['load_not_16k_count'], 1)
        self.assertEqual(census['load_16k_count'], 1)
        self.assertFalse(census['runtime_compatibility_proven'])
        self.assertNotIn('one.so', str(census))
        self.assertNotIn('two.so', str(census))

    def test_elf_32bit_big_endian_and_corrupt_segment_bounds_fail_closed(self):
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from private_payload_parity import _elf_load_alignment
        blob = bytearray(84)
        blob[:16] = b'\x7fELF' + bytes([1,2,1]) + bytes(9)
        struct.pack_into('>I', blob, 28, 52)
        struct.pack_into('>HH', blob, 42, 32, 1)
        struct.pack_into('>IIIIIIII', blob, 52,
                         1, 0, 0, 0, 1, 1, 5, 16384)
        self.assertEqual(_elf_load_alignment(bytes(blob)), 'LOAD_16K')
        struct.pack_into('>I', blob, 28, 9999999)
        self.assertEqual(_elf_load_alignment(bytes(blob)), 'UNVERIFIED')
        self.assertEqual(_elf_load_alignment(None), 'UNVERIFIED')

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
