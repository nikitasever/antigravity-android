import copy
import hashlib
import io
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from release_artifacts import compare_payload, validate_request, download


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
        for parts in [[], ["http://example.test/a"], ["https://example.test/asset.apk"] * 9,
                      ["https://example.test/asset.apk", "https://user:secret@example.test/b"]]:
            request = self.request(); request["assets"][0]["parts"] = parts
            with self.assertRaises(ValueError): validate_request(request, gradle)

    def test_parts_are_concatenated_in_order_and_whole_apk_digest_is_mandatory(self):
        class Response(io.BytesIO):
            status = 200
            def __init__(self, data):
                super().__init__(data); self.headers = {"Content-Length": str(len(data))}
        class Opener:
            def open(self, request, timeout): return Response({"https://example.test/a": b"first", "https://example.test/b": b"second"}[request.full_url])
        with tempfile.TemporaryDirectory(prefix="release-parts-") as directory, patch("release_artifacts.urllib.request.build_opener", return_value=Opener()):
            target = pathlib.Path(directory) / "signed.apk"
            asset = {"url": "https://example.test/a", "parts": ["https://example.test/a", "https://example.test/b"],
                     "sha256": hashlib.sha256(b"firstsecond").hexdigest()}
            download(asset, target); self.assertEqual(b"firstsecond", target.read_bytes())
            asset["url"] = "https://example.test/b"; asset["parts"].reverse()
            with self.assertRaises(ValueError): download(asset, target)
            self.assertEqual(b"firstsecond", target.read_bytes())
            self.assertFalse(target.with_suffix(".part").exists())

    def test_truncated_part_never_replaces_existing_apk(self):
        class Response(io.BytesIO):
            status = 200
            headers = {"Content-Length": "100"}
        class Opener:
            def open(self, request, timeout): return Response(b"short")
        with tempfile.TemporaryDirectory(prefix="release-truncated-") as directory, patch("release_artifacts.urllib.request.build_opener", return_value=Opener()):
            target = pathlib.Path(directory) / "signed.apk"; target.write_bytes(b"existing")
            with self.assertRaises(ValueError): download({"url": "https://example.test/a", "sha256": "a" * 64}, target)
            self.assertEqual(b"existing", target.read_bytes()); self.assertFalse(target.with_suffix(".part").exists())

    def test_payload_ignores_only_signatures_and_detects_changed_code_or_resources(self):
        with tempfile.TemporaryDirectory(prefix="release-verification-") as directory:
            original, signed = pathlib.Path(directory) / "built.apk", pathlib.Path(directory) / "signed.apk"
            def write(path, dex=b"code", resources=b"resources", extra=False, revision=None):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("classes.dex", dex); archive.writestr("resources.arsc", resources)
                    if extra: archive.writestr("META-INF/CERT.RSA", b"signature")
                    if revision: archive.writestr("META-INF/version-control-info.textproto", revision)
            write(original); write(signed, extra=True); compare_payload(original, signed)
            for args in [(b"changed", b"resources"), (b"code", b"changed")]:
                write(signed, *args)
                with self.assertRaises(ValueError): compare_payload(original, signed)
            # Do not conceal machine/checkout metadata differences in the verifier.
            write(original, revision=b"commit-a"); write(signed, revision=b"commit-b")
            with self.assertRaises(ValueError): compare_payload(original, signed)


if __name__ == "__main__": unittest.main()
