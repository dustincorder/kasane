#!/usr/bin/env python3
"""Exercise upgrade preflight with fake Android tools; never invokes real ADB."""
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('android-safe-install.sh')

class SafeInstallTest(unittest.TestCase):
    def run_case(self, mismatch=False, installed=True, install_failure=False):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            log = root / 'adb.log'
            apk = root / 'candidate.apk'
            apk.touch()
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import os, sys, pathlib
args=sys.argv[1:]
with open(os.environ['TEST_LOG'],'a') as f: f.write(' '.join(args)+'\\n')
if args[0]=='devices': print('test-device device')
elif args[2:5]==['shell','pm','path']:
 if os.environ['TEST_INSTALLED']=='1': print('package:/data/app/base.apk')
elif args[2]=='pull': pathlib.Path(args[-1]).touch()
elif args[2]=='install': sys.exit(int(os.environ['TEST_INSTALL_FAILURE']))
else: sys.exit(99)
''')
            aapt = root / 'aapt'
            aapt.write_text("#!/bin/sh\necho \"package: name='cord.kasane.music' versionCode='2'\"\n")
            signer = root / 'apksigner'
            signer.write_text('''#!/usr/bin/env python3
import os,sys
value='aaa'
if os.environ['TEST_MISMATCH']=='1' and sys.argv[-1].endswith('candidate.apk'): value='bbb'
print('Signer #1 certificate SHA-256 digest: '+value)
''')
            for tool in (adb, aapt, signer): tool.chmod(0o755)
            env = dict(os.environ, ADB=str(adb), AAPT=str(aapt), APKSIGNER=str(signer),
                       TEST_LOG=str(log), TEST_MISMATCH=str(int(mismatch)),
                       TEST_INSTALLED=str(int(installed)), TEST_INSTALL_FAILURE=str(int(install_failure)))
            result = subprocess.run(['bash', str(SCRIPT), 'test-device', str(apk)], env=env, capture_output=True)
            calls = log.read_text()
            self.assertNotIn('uninstall', calls)
            self.assertNotIn('clear', calls)
            return result.returncode, calls

    def test_matching_certificate_allows_only_in_place_update(self):
        code,calls=self.run_case()
        self.assertEqual(0,code)
        self.assertEqual(1,calls.count(' install -r '))

    def test_mismatch_prevents_install(self):
        code,calls=self.run_case(mismatch=True)
        self.assertEqual(8,code)
        self.assertNotIn(' install ',calls)

    def test_missing_package_prevents_fresh_install(self):
        code,calls=self.run_case(installed=False)
        self.assertEqual(5,code)
        self.assertNotIn(' install ',calls)

    def test_install_failure_never_falls_back_to_uninstall(self):
        code,calls=self.run_case(install_failure=True)
        self.assertNotEqual(0,code)
        self.assertEqual(1,calls.count(' install -r '))

if __name__=='__main__': unittest.main()
