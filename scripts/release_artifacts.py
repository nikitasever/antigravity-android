"""Verify locally signed APKs against independent CI builds; private keys stay off CI."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.parse
import urllib.request
import zipfile

MAX_APK = 300 * 1024 * 1024
NAMES = {"release": "Antigravity-Android.apk", "debug": "Antigravity-Android-debug-local.apk"}


def asset_urls(asset):
    urls = asset.get("parts", [asset.get("url")])
    if not isinstance(urls, list) or not 1 <= len(urls) <= 8 or urls[0] != asset.get("url"):
        raise ValueError("Invalid artifact parts")
    for value in urls:
        if not isinstance(value, str):
            raise ValueError("Invalid artifact URL")
        url = urllib.parse.urlsplit(value)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment or url.port not in (None, 443):
            raise ValueError("Artifact URL must be credential-free HTTPS")
    return urls


def validate_request(request, gradle):
    version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle).group(1))
    if request.get("tag") != "v" + version or not re.fullmatch(r"v\d+\.\d+\.\d+", request["tag"]):
        raise ValueError("Release tag/version mismatch")
    assets = request.get("assets")
    if not isinstance(assets, list) or len(assets) != 2:
        raise ValueError("Both signed variants are required")
    variants = set()
    for asset in assets:
        variant = asset.get("variant")
        if variant not in NAMES or variant in variants or asset.get("name") != NAMES[variant]:
            raise ValueError("Invalid/duplicate artifact variant/name")
        variants.add(variant)
        for key in ("sha256", "certificateSha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", asset.get(key, "")):
                raise ValueError("Invalid artifact digest")
        asset_urls(asset)
    return version, code


def file_digest(path):
    value = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


class HttpsOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != "https" or parsed.username or parsed.password or parsed.port not in (None, 443):
            raise ValueError("Unsafe artifact redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(asset, destination):
    temporary = destination.with_suffix(".part")
    try:
        urls = asset_urls(asset)
        count = 0
        with temporary.open("wb") as output:
            for url in urls:
                request = urllib.request.Request(url, headers={"User-Agent": "Antigravity-Release-Verification"})
                with urllib.request.build_opener(HttpsOnlyRedirect).open(request, timeout=120) as response:
                    if response.status != 200:
                        raise ValueError("Artifact must be a complete HTTP 200 response")
                    expected = int(response.headers.get("Content-Length", "-1"))
                    if expected > MAX_APK - count:
                        raise ValueError("Artifact exceeds size limit")
                    part_count = 0
                    while block := response.read(1024 * 1024):
                        count += len(block)
                        part_count += len(block)
                        if count > MAX_APK:
                            raise ValueError("Artifact exceeds size limit")
                        output.write(block)
                    if part_count == 0 or expected >= 0 and expected != part_count:
                        raise ValueError("Incomplete artifact")
        if file_digest(temporary) != asset["sha256"]:
            raise ValueError("Artifact SHA-256 mismatch")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def payload(path):
    result = {}
    with zipfile.ZipFile(path) as archive:
        names = set()
        for entry in archive.infolist():
            if entry.filename in names:
                raise ValueError("Duplicate APK entry")
            names.add(entry.filename)
            # v1 signatures differ; v2/v3 signing blocks are outside ZIP entries.
            if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", entry.filename, re.I) or entry.is_dir():
                continue
            digest = hashlib.sha256()
            with archive.open(entry) as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
            result[entry.filename] = digest.hexdigest()
    return result


def compare_payload(built, signed):
    original, published = payload(built), payload(signed)
    differences = sorted(name for name in original.keys() | published.keys() if original.get(name) != published.get(name))
    if differences:
        raise ValueError("APK payload differs from CI build: " + ", ".join(differences[:20]))


def verify_identity(apksigner, aapt, apk, certificate, version, code):
    signed = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)], check=True, capture_output=True, text=True).stdout
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", signed)
    if fingerprints != [certificate]:
        raise ValueError("APK signer mismatch")
    manifest = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", manifest)
    if not match or match.groups() != ("com.google.antigravity", str(code), version):
        raise ValueError("APK package/version mismatch")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", default=".github/release.json")
    parser.add_argument("--sdk", required=True)
    args = parser.parse_args()
    request = json.loads(pathlib.Path(args.request).read_text(encoding="utf-8"))
    version, code = validate_request(request, pathlib.Path("app/build.gradle.kts").read_text(encoding="utf-8"))
    sdk = pathlib.Path(args.sdk)
    tools = max((p for p in (sdk / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)),
                key=lambda p: tuple(map(int, p.name.split("."))))
    for asset in request["assets"]:
        destination = pathlib.Path(asset["name"])
        download(asset, destination)
        built = pathlib.Path("app/build/outputs/apk") / asset["variant"] / ("app-release-unsigned.apk" if asset["variant"] == "release" else "app-debug.apk")
        compare_payload(built, destination)
        verify_identity(tools / "apksigner", tools / "aapt", destination, asset["certificateSha256"], version, code)
        print(f"Verified {asset['variant']}: SHA, signer, package/version and complete APK payload match")
    pathlib.Path("SHA256SUMS.txt").write_text("".join(f"{asset['sha256']}  {asset['name']}\n" for asset in request["assets"]), encoding="utf-8")


if __name__ == "__main__":
    main()
