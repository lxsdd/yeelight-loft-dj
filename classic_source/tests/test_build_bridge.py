"""No toolchain download/installation and no device action: fail-closed cross-platform guard."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
from bridge.build_bridge import build, sdk_file, ToolError

class BuildBridgeGuards(unittest.TestCase):
    def test_missing_sdk_fails_without_output_or_network(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)
            with self.assertRaisesRegex(ToolError,'ANDROID_SDK_MISSING'):
                build(path,path/'bridge-build')
            self.assertFalse((path/'bridge-build').exists())

    def test_refuses_existing_output_before_tool_invocation(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)
            (path/'bridge-build').mkdir()
            with self.assertRaisesRegex(ToolError,'OUTPUT_ALREADY_EXISTS'):
                build(path,path/'bridge-build')
