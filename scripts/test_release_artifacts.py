import copy
import pathlib
import tempfile
import unittest
import zipfile
from release_artifacts import compare_payload, validate_request


class ReleaseArtifactsTest(unittest.TestCase):
    def request(self):
        return {"tag": "v2.3.0", "assets": [{"variant": variant, "name": name, "url": "https://example.test/asset.apk",
            "sha256": "a" * 64, "certificateSha256": "b" * 64} for variant, name in
            [("release", "Antigravity-Android.apk"), ("debug", "Antigravity-Android-debug-local.apk")]]}

    def test_request_checks_version_variants_digests_and_url(self):
        gradle = 'versionName = "2.3.0"\nversionCode = 20261109'
        self.assertEqual(("2.3.0", 20261109), validate_request(self.request(), gradle))
        for url in ["http://example.test/a.apk", "https://user:secret@example.test/a.apk", "https://example.test:8443/a.apk"]:
            request = self.request(); request["assets"][0]["url"] = url
            with self.assertRaises(ValueError): validate_request(request, gradle)
        for mutate in [lambda r: r.update(tag="v2.2.0"), lambda r: r["assets"][0].update(name="../x.apk"),
                       lambda r: r["assets"][0].update(sha256="invalid"), lambda r: r["assets"].append(copy.deepcopy(r["assets"][0]))]:
            request = self.request(); mutate(request)
            with self.assertRaises(ValueError): validate_request(request, gradle)

    def test_payload_ignores_only_signatures_and_detects_changed_code_or_resources(self):
        with tempfile.TemporaryDirectory(prefix="release-verification-") as directory:
            original, signed = pathlib.Path(directory) / "built.apk", pathlib.Path(directory) / "signed.apk"
            def write(path, dex=b"code", resources=b"resources", extra=False):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("classes.dex", dex); archive.writestr("resources.arsc", resources)
                    if extra: archive.writestr("META-INF/CERT.RSA", b"signature")
            write(original); write(signed, extra=True); compare_payload(original, signed)
            for args in [(b"changed", b"resources"), (b"code", b"changed")]:
                write(signed, *args)
                with self.assertRaises(ValueError): compare_payload(original, signed)


if __name__ == "__main__": unittest.main()
