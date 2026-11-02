from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class OfflineRepositoryTest(unittest.TestCase):
    def test_exclusive_dependency_uses_the_declared_offline_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / 'offline-repository/fixture/payload/1.0'
            artifact.mkdir(parents=True)
            (artifact / 'payload-1.0.jar').write_text('fixture payload')
            (artifact / 'payload-1.0.pom').write_text('''<project>
<modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
<artifactId>payload</artifactId><version>1.0</version></project>''')
            (root / 'settings.gradle').write_text('''
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository { maven { url = 'https://example.invalid/exclusive' } }
            filter { includeGroup('fixture') }
        }
    }
}
''')
            (root / 'build.gradle').write_text('''
configurations.create('payload')
dependencies.add('payload', 'fixture:payload:1.0')
tasks.register('resolvePayload') {
    doLast { assert configurations.payload.singleFile.text == 'fixture payload' }
}
''')
            result = subprocess.run([str(ROOT / 'gradlew'), '--offline', '--no-daemon',
                                     '--no-configuration-cache', '--max-workers=1',
                                     '-Dorg.gradle.jvmargs=-Xmx256m', '-p', str(root), '-I',
                                     str(ROOT / 'packaging/flatpak/offline.init.gradle'),
                                     'resolvePayload'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
