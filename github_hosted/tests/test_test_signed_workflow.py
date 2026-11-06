import pathlib
import unittest
import yaml

ROOT=pathlib.Path(__file__).resolve().parents[2]
YAML=ROOT/'.github/workflows/classic-private-test-signed.yml'

class TestSignedPrivateWorkflow(unittest.TestCase):
    def test_only_manual_owner_main_with_required_environment(self):
        s=YAML.read_text('utf-8');w=yaml.load(s,Loader=yaml.BaseLoader)
        self.assertEqual(list(w['on']),['workflow_dispatch'])
        self.assertEqual(w['permissions'],{'contents':'read'})
        self.assertNotIn('pull_request_target',s)
        self.assertNotIn('actions/upload-artifact',s)
        self.assertNotIn('gh release create',s)
        self.assertNotIn('gh release upload',s)
        j=w['jobs']['guarded-private-build']
        self.assertIn('github.actor == \'lxsdd\'',j['if'])
        self.assertIn("github.ref == 'refs/heads/main'",j['if'])
        self.assertEqual(j['environment']['name'],'classic-private-build')
        self.assertEqual(j['environment']['deployment'],'false')
        self.assertEqual(j['runs-on'],'ubuntu-latest')
        self.assertEqual(w['concurrency']['cancel-in-progress'],'false')
    def test_private_token_never_used_by_signer_compiler(self):
        w=yaml.load(YAML.read_text('utf-8'),Loader=yaml.BaseLoader)
        steps=w['jobs']['guarded-private-build']['steps']
        secret=[x for x in steps if '${{ secrets.CLASSIC_VAULT_TOKEN }}' in str(x)]
        self.assertEqual(len(secret),2)
        self.assertTrue(all(x.get('env',{})=={'GH_TOKEN':'${{ secrets.CLASSIC_VAULT_TOKEN }}'} for x in secret))
        names={x['name']:x for x in steps}
        self.assertIn('Sign one private test APK with a fresh ephemeral certificate',names)
        self.assertIn('Send TEST-SIGNED APK to PRIVATE draft Release only',names)
        self.assertIn('private_test_sign.py',names['Sign one private test APK with a fresh ephemeral certificate']['run'])
        self.assertIn('publish_private_signed.sh',names['Send TEST-SIGNED APK to PRIVATE draft Release only']['run'])
        self.assertNotIn('GH_TOKEN',str(names['Sign one private test APK with a fresh ephemeral certificate']))
        self.assertNotIn('CLASSIC_VAULT_TOKEN',str(names['Build and verify private unsigned APK; zero vault credentials']))
        self.assertEqual(names['Setup pinned Gradle, no persistent private build cache']['with']['cache-disabled'],'true')

if __name__=='__main__':unittest.main()
