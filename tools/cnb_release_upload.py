#!/usr/bin/env python3
"""CNB Release publisher: local preflight first, fail closed, never promote a test release.

python tools/cnb_release_upload.py --tag v0.27.0 --dir releases/v0.27.0 \
    --target-commit <commit-sha> --prerelease --no-latest
Credentials are resolved lazily, never printed or persisted by this program.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass

REPO = "lzyr9010/hongguoTV-updates"
API = "https://api.cnb.cool"
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.hongguotv.nativeapp"
TOKEN_NAMES = ("CNB_API_TOKEN", "API_TOKEN", "CNB_TOKEN")
MAX_APK_BYTES = 80 * 1024 * 1024


class ReleaseError(Exception):
    """Only locally constructed, credential-safe messages reach CLI output."""


def _checked_token(value):
    if value is None or value == "":
        return None
    if not isinstance(value, str):
        raise ReleaseError("Invalid credential value")
    value = value.strip()
    if not value:
        return None
    if not value.isascii() or any(ord(c) <= 32 or ord(c) >= 127 for c in value):
        raise ReleaseError("Invalid credential format")
    return value


def _windows_user_token():
    if os.name != "nt":
        return None
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment", 0, winreg.KEY_READ) as key:
            value, _ = winreg.QueryValueEx(key, "CNB_API_TOKEN")
            return value
    except (OSError, ImportError):
        return None


def token(environ=None, windows_reader=None, credentials_path=None):
    """Process env -> explicitly authorized Windows User env -> ignored legacy file."""
    environ = os.environ if environ is None else environ
    for name in TOKEN_NAMES:
        value = _checked_token(environ.get(name))
        if value:
            return value
    value = _checked_token((windows_reader or _windows_user_token)())
    if value:
        return value
    path = Path(credentials_path) if credentials_path is not None else ROOT / ".cnb-credentials"
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raw = ""
    except (OSError, UnicodeError):
        raise ReleaseError("Unable to read ignored legacy credentials") from None
    if len(raw) > 16_384:
        raise ReleaseError("Invalid legacy credentials file")
    for line in raw.splitlines():
        try:
            parsed = urllib.parse.urlsplit(line.strip())
            if parsed.scheme == "https" and parsed.hostname == "cnb.cool" and parsed.password:
                value = _checked_token(urllib.parse.unquote(parsed.password))
                if value:
                    return value
        except ValueError:
            continue
    raise ReleaseError("CNB credentials unavailable; set CNB_API_TOKEN, API_TOKEN or CNB_TOKEN")


def validate_tag(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value) or ".." in value:
        raise ReleaseError("Invalid release tag")
    return value


def validate_asset_name(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,179}", value) or ".." in value:
        raise ReleaseError("Invalid asset name")
    return value


def validate_target_commit(value):
    if value is not None and not re.fullmatch(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})", value):
        raise ReleaseError("--target-commit requires a full commit SHA")
    return value or "main"


def _safe_https(url, api=False):
    try:
        if not isinstance(url, str) or any(ord(c) <= 32 or ord(c) == 127 for c in url):
            raise ValueError()
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.fragment or parsed.port not in (None, 443):
            raise ValueError()
        decoded = urllib.parse.unquote(parsed.path)
        if "\\" in decoded or any(segment in (".", "..") for segment in decoded.split("/")):
            raise ValueError()
        prefix = f"/{REPO}/-/releases"
        if any(ord(c) <= 32 or ord(c) == 127 for c in decoded):
            raise ValueError()
        if api and (parsed.hostname != "api.cnb.cool" or not (decoded == prefix or decoded.startswith(prefix + "/"))):
            raise ValueError()
    except (TypeError, ValueError):
        raise ReleaseError("Untrusted API URL" if api else "Untrusted upload URL") from None
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class HttpClient:
    def __init__(self, token_provider=token, opener=None):
        self._token_provider = token_provider
        self._token = None
        self._opener = opener or urllib.request.build_opener(NoRedirect())

    def _open(self, request, timeout):
        try:
            with self._opener.open(request, timeout=timeout) as response:
                return response.status, response.read(1024 * 1024 + 1), response.headers
        except urllib.error.HTTPError as error:
            # Bodies may echo Authorization, signed URL parameters or other secrets. Never log them.
            return error.code, error.read(1024 * 1024 + 1), error.headers
        except (OSError, urllib.error.URLError, TimeoutError, http.client.HTTPException):
            raise ReleaseError("Release network request failed") from None

    def call(self, method, url, body=None, content_type="application/json", timeout=60):
        _safe_https(url, api=True)
        if self._token is None:
            self._token = _checked_token(self._token_provider())
            if not self._token:
                raise ReleaseError("CNB credentials unavailable")
        data = body if isinstance(body, (bytes, bytearray)) or body is None else json.dumps(body).encode("utf-8")
        for attempt in range(6):
            request = urllib.request.Request(url, data=data, method=method)
            request.add_header("Authorization", "Bearer " + self._token)
            request.add_header("Accept", "application/json")
            if data is not None:
                request.add_header("Content-Type", content_type)
            status, payload, headers = self._open(request, timeout)
            if len(payload) > 1024 * 1024:
                raise ReleaseError("Release API response is too large")
            if status not in (301, 302, 303, 307, 308):
                return status, payload
            # Only read requests can redirect, and the destination must stay HTTPS on this API/repo.
            if method != "GET" or attempt == 5 or not headers.get("Location"):
                raise ReleaseError(f"Release API redirect rejected (HTTP {status})")
            url = _safe_https(urllib.parse.urljoin(url, headers["Location"]), api=True)
        raise ReleaseError("Release API redirect limit exceeded")

    def put(self, url, asset):
        _safe_https(url)
        with asset.path.open("rb") as source:
            request = urllib.request.Request(url, data=source, method="PUT")
            request.add_header("Content-Type", "application/octet-stream")
            request.add_header("Content-Length", str(asset.size))
            # Deliberately no Authorization and no automatic redirects for presigned object storage.
            status, _, _ = self._open(request, 600)
        _expect(status, "upload")


def _expect(status, operation):
    if not 200 <= status < 300:
        raise ReleaseError(f"{operation} failed (HTTP {status})")


def _json(payload, operation):
    try:
        parsed = json.loads(payload)
        if not isinstance(parsed, dict):
            raise ValueError()
        return parsed
    except (ValueError, TypeError, UnicodeError):
        raise ReleaseError(f"Invalid {operation} response") from None


def _release_key(value):
    if isinstance(value, int) and not isinstance(value, bool):
        value = str(value)
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise ReleaseError("Invalid release identifier")
    return value


def _hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


@dataclass(frozen=True)
class Asset:
    path: Path
    name: str
    size: int
    sha256: str


def _find_aapt():
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(name)
        if not sdk:
            continue
        folder = Path(sdk) / "build-tools"
        if folder.is_dir():
            versions = sorted(folder.iterdir(), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
            for version in versions:
                for binary in ("aapt.exe", "aapt"):
                    candidate = version / binary
                    if candidate.is_file():
                        return str(candidate)
    found = shutil.which("aapt")
    if found:
        return found
    raise ReleaseError("Android aapt is required to verify APK versions (use --aapt)")


def apk_metadata(path, aapt=None):
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)) or "AndroidManifest.xml" not in names or "classes.dex" not in names:
                raise ValueError()
            if archive.getinfo("AndroidManifest.xml").file_size > 2 * 1024 * 1024:
                raise ValueError()
    except (OSError, ValueError, zipfile.BadZipFile):
        raise ReleaseError("Invalid APK container") from None
    env = {k: v for k, v in os.environ.items() if k not in TOKEN_NAMES}
    try:
        result = subprocess.run([aapt or _find_aapt(), "dump", "badging", str(path)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30, env=env)
    except (OSError, subprocess.SubprocessError):
        raise ReleaseError("APK metadata inspection failed") from None
    if result.returncode or len(result.stdout) > 2 * 1024 * 1024:
        raise ReleaseError("APK metadata inspection failed")
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", result.stdout, re.M)
    sdk = re.search(r"^sdkVersion:'([0-9]+)'", result.stdout, re.M)
    if not package or not sdk:
        raise ReleaseError("APK version metadata missing")
    return {"packageName": package[1], "versionCode": int(package[2]), "versionName": package[3], "minSdk": int(sdk[1])}


def preflight(directory, tag, metadata_reader=apk_metadata):
    validate_tag(tag)
    try:
        folder = Path(directory).resolve(strict=True)
        if not folder.is_dir() or str(folder).startswith("\\\\"):
            raise ValueError()
        files = sorted(p for p in folder.iterdir() if p.is_file() or p.is_symlink())
    except (OSError, ValueError):
        raise ReleaseError("Invalid local release directory") from None
    if not files:
        raise ReleaseError("Release directory is empty")
    assets = {}
    for path in files:
        validate_asset_name(path.name)
        if path.is_symlink() or path.resolve().parent != folder:
            raise ReleaseError("Release assets must be regular local files")
        size = path.stat().st_size
        if size <= 0 or (path.suffix.lower() == ".apk" and size > MAX_APK_BYTES):
            raise ReleaseError("Invalid release asset size")
        assets[path.name] = Asset(path, path.name, size, _hash(path))
    if "update.json" not in assets or assets["update.json"].size > 1024 * 1024:
        raise ReleaseError("A bounded update.json is required")
    try:
        raw = assets["update.json"].path.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"):
            raise ValueError()
        doc = json.loads(raw.decode("utf-8"))
        variants = doc["variants"]
        version = doc["versionName"]
        if not isinstance(variants, dict) or not variants or not isinstance(version, str) or not re.fullmatch(r"[0-9][0-9A-Za-z.+-]{0,63}", version):
            raise ValueError()
        code = doc["versionCode"]
        if type(code) is not int or not 1 <= code <= 2**31 - 1:
            raise ValueError()
        if (tag.startswith("v") and tag[1:] != version) or ("tag" in doc and doc["tag"] != tag):
            raise ValueError()
    except (ValueError, KeyError, TypeError, UnicodeError):
        raise ReleaseError("Invalid update.json release metadata") from None
    described = set()
    for key, row in variants.items():
        validate_asset_name(key)
        if not isinstance(row, dict):
            raise ReleaseError("Invalid update variant")
        name = validate_asset_name(row.get("file"))
        if name in described or name not in assets or not name.endswith(".apk"):
            raise ReleaseError("Missing, duplicate or non-APK update variant")
        described.add(name)
        asset = assets[name]
        if type(row.get("size")) is not int or row["size"] != asset.size or not isinstance(row.get("sha256"), str) or not re.fullmatch(r"[0-9a-fA-F]{64}", row["sha256"]) or row["sha256"].lower() != asset.sha256:
            raise ReleaseError("APK size or SHA-256 does not match update.json")
        row_version, row_code = row.get("versionName"), row.get("versionCode")
        if not isinstance(row_version, str) or not re.fullmatch(r"[0-9][0-9A-Za-z.+-]{0,63}", row_version) or type(row_code) is not int or not 1 <= row_code <= 2**31 - 1 or not name.startswith(f"hongguotv-{row_version}-"):
            raise ReleaseError("Invalid APK version metadata")
        if key != "a5" and (row_version != version or row_code != code):
            raise ReleaseError("Main APK version does not match the release version")
        actual = metadata_reader(asset.path)
        if actual.get("packageName") != PACKAGE or actual.get("versionName") != row_version or actual.get("versionCode") != row_code or ("minSdk" in row and row["minSdk"] != actual.get("minSdk")):
            raise ReleaseError("APK manifest does not match update.json; rebuild instead of renaming")
    if not any(key != "a5" for key in variants):
        raise ReleaseError("Release has no current-version APK")
    if described != {name for name in assets if name.endswith(".apk")}:
        raise ReleaseError("Unlisted APK in release directory")
    if "SHA256SUMS.txt" in assets:
        listed = set()
        try:
            lines = assets["SHA256SUMS.txt"].path.read_text(encoding="utf-8").splitlines()
            for line in lines:
                if not line.strip():
                    continue
                match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+\*?([A-Za-z0-9][A-Za-z0-9._-]{0,179})", line)
                if not match or match[2] in listed or match[2] not in assets or match[1].lower() != assets[match[2]].sha256:
                    raise ValueError()
                validate_asset_name(match[2]); listed.add(match[2])
            if not described.issubset(listed):
                raise ValueError()
        except (ValueError, UnicodeError):
            raise ReleaseError("Invalid SHA256SUMS.txt") from None
    return [assets[name] for name in sorted(assets, key=lambda n: (n == "update.json", n))]


def release_id(client, tag, title=None, notes=None, target_commit=None, prerelease=False):
    tag = validate_tag(tag)
    status, payload = client.call("GET", f"{API}/{REPO}/-/releases/tags/{tag}")
    if status == 200:
        release = _json(payload, "release")
        if prerelease and release.get("prerelease") is not True:
            raise ReleaseError("Refusing to change an existing stable release in test mode")
        if target_commit and release.get("target_commitish") != target_commit:
            raise ReleaseError("Existing release targets a different commit")
        return _release_key(release.get("id")), release
    if status != 404:
        _expect(status, "release lookup")
        raise ReleaseError("Unexpected release lookup response")
    status, payload = client.call("POST", f"{API}/{REPO}/-/releases", {
        "tag_name": tag, "name": title or tag, "body": notes or "",
        "target_commitish": validate_target_commit(target_commit),
        # Start every release as a non-latest prerelease. Stable promotion only follows ALL uploads.
        "prerelease": True, "make_latest": "false",
    })
    _expect(status, "release creation")
    release = _json(payload, "release creation")
    if release.get("prerelease") is False or (target_commit and release.get("target_commitish", target_commit) != target_commit):
        raise ReleaseError("Created release does not match requested safety settings")
    return _release_key(release.get("id")), release


def upload(client, release, asset, overwrite=False):
    release = _release_key(release)
    if asset.path.stat().st_size != asset.size or _hash(asset.path) != asset.sha256:
        raise ReleaseError("Release asset changed after preflight")
    prefix = f"{API}/{REPO}/-/releases/{release}/"
    status, payload = client.call("POST", prefix + "asset-upload-url", {"asset_name": asset.name, "overwrite": overwrite, "size": asset.size})
    _expect(status, "upload URL")
    info = _json(payload, "upload URL")
    upload_url = _safe_https(info.get("upload_url"))
    verify_url = _safe_https(info.get("verify_url"), api=True)
    if not urllib.parse.unquote(urllib.parse.urlsplit(verify_url).path).startswith(f"/{REPO}/-/releases/{release}/"):
        raise ReleaseError("Verification URL targets another release")
    client.put(upload_url, asset)
    if asset.path.stat().st_size != asset.size or _hash(asset.path) != asset.sha256:
        raise ReleaseError("Release asset changed during upload")
    status, payload = client.call("POST", verify_url, b"")
    _expect(status, "upload verification")
    if payload.strip():
        result = _json(payload, "upload verification")
        if result.get("success") is False or result.get("error") or ("code" in result and result["code"] not in (0, 200)):
            raise ReleaseError("Upload verification rejected the asset")
    print(f"  + {asset.name} ({asset.size / 1048576:.1f} MB)")


def promote(client, release):
    status, payload = client.call("PATCH", f"{API}/{REPO}/-/releases/{release}", {"prerelease": False, "make_latest": "true"})
    _expect(status, "latest promotion")
    if payload.strip() and _json(payload, "promotion").get("prerelease") is True:
        raise ReleaseError("Promotion did not clear prerelease status")
    status, payload = client.call("GET", f"{API}/{REPO}/-/releases/latest")
    _expect(status, "latest confirmation")
    latest = _json(payload, "latest confirmation")
    if _release_key(latest.get("id")) != release or latest.get("prerelease") is True:
        raise ReleaseError("Latest release confirmation does not match")


def main(argv=None, client=None, metadata_reader=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--target-commit", help="full source commit SHA; defaults to main for legacy callers")
    parser.add_argument("--prerelease", action="store_true", help="keep this release non-latest and marked prerelease")
    parser.add_argument("--no-latest", action="store_true", help="implies prerelease; never patch/promote latest")
    parser.add_argument("--overwrite", action="store_true", help="explicitly permit replacing existing attachments")
    parser.add_argument("--dry-run", action="store_true", help="local verification only; no credentials or network")
    parser.add_argument("--aapt", help="existing Android aapt executable for manifest verification")
    parser.add_argument("--title")
    parser.add_argument("--notes", default="")
    args = parser.parse_args(argv)
    try:
        validate_tag(args.tag); validate_target_commit(args.target_commit)
        reader = metadata_reader or (lambda path: apk_metadata(path, args.aapt))
        assets = preflight(args.dir, args.tag, reader)
        if args.dry_run:
            print(f"preflight passed: {args.tag}, {len(assets)} assets; no remote changes")
            return 0
        client = client or HttpClient()
        is_test = args.prerelease or args.no_latest
        release, existing = release_id(client, args.tag, args.title, args.notes, args.target_commit, is_test)
        present = existing.get("assets", [])
        if not isinstance(present, list):
            raise ReleaseError("Invalid existing release asset list")
        collisions = {item.get("name") for item in present if isinstance(item, dict)} & {asset.name for asset in assets}
        if collisions and not args.overwrite:
            raise ReleaseError("Release already contains local asset names; use --overwrite explicitly")
        for asset in assets:
            upload(client, release, asset, args.overwrite)
        if not is_test:
            promote(client, release)
        print(f"uploaded {len(assets)}/{len(assets)}; " + ("prerelease retained, latest untouched" if is_test else "latest confirmed"))
        return 0
    except ReleaseError as error:
        print(f"release failed: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError):
        print("release failed: local or API validation failed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
