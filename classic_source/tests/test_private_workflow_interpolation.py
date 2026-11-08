"""Regression for owner-only private audit workflow GitHub expression wrappers.

The public source CI runs these checks without fetching any private OEM assets.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / '.github/workflows/classic-private-startup-audit.yml'


def validate_expression_wrappers(text: str) -> None:
    """Refuse single-braced GitHub expressions that YAML parsers can miss."""
    expressions = re.findall(r'\$\{\{([^{}\n]+)\}\}', text)
    if re.search(r'\$\{(?!\{)', text):
        raise ValueError('MALFORMED_GITHUB_EXPRESSION')
    if '${' in re.sub(r'\$\{\{[^{}\n]+\}\}', '', text):
        raise ValueError('UNTERMINATED_GITHUB_EXPRESSION')
    if len(expressions) < 5:
        raise ValueError('GITHUB_EXPRESSION_COUNT_CHANGED')


class TestPrivateWorkflowInterpolation(unittest.TestCase):
    def test_current_private_audit_uses_only_valid_expressions(self):
        validate_expression_wrappers(AUDIT.read_text(encoding='utf-8'))

    def test_original_malformed_wrapper_is_rejected(self):
        valid = AUDIT.read_text(encoding='utf-8')
        malformed = valid.replace('${{ secrets.CLASSIC_VAULT_TOKEN }}',
                                  '${ secrets.CLASSIC_VAULT_TOKEN }}')
        with self.assertRaisesRegex(ValueError, 'MALFORMED_GITHUB_EXPRESSION'):
            validate_expression_wrappers(malformed)

    def test_missing_closing_brace_is_rejected(self):
        valid = AUDIT.read_text(encoding='utf-8')
        malformed = valid.replace('${{ github.sha }}', '${{ github.sha }')
        with self.assertRaisesRegex(ValueError, 'UNTERMINATED_GITHUB_EXPRESSION'):
            validate_expression_wrappers(malformed)

    def test_protected_owner_context_and_private_inputs(self):
        text = AUDIT.read_text(encoding='utf-8')
        required = (
            'run-name: Classic private startup audit ${{ github.sha }}',
            "if: ${{ github.actor == 'lxsdd' && github.ref == 'refs/heads/main' && github.repository == 'lxsdd/yeelight-loft-dj' && github.event.repository.private == false }}",
            'GH_TOKEN: ${{ secrets.CLASSIC_VAULT_TOKEN }}',
            'CLASSIC_ORIGINAL_APK: ${{ runner.temp }}/classic-original/com.yeelight.cherry_3.5.4.apk',
            'CLASSIC_APKTOOL_JAR: ${{ runner.temp }}/classic-tools/apktool_3.0.3.jar',
        )
        for entry in required:
            with self.subTest(entry=entry):
                self.assertIn(entry, text)
        self.assertIn('  workflow_dispatch:', text)
        self.assertNotIn('\n  pull_request:', text)
        self.assertNotIn('\n  push:', text)
        self.assertIn('name: classic-private-build', text)
        self.assertIn('deployment: false', text)
        self.assertIn('persist-credentials: false', text)
        self.assertNotIn('upload-artifact', text)
        self.assertNotIn('private_test_sign.py', text)
        self.assertNotIn('publish_private_signed.sh', text)

if __name__ == '__main__':
    unittest.main()
