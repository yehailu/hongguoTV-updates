#!/usr/bin/env python3
"""CNB Release 附件上传 + 设为最新（走 OpenAPI，PAT 从仓库 .cnb-credentials 读取）。

用法：
  python tools/cnb_release_upload.py --tag v0.26.3 --dir releases/v0.26.3
  python tools/cnb_release_upload.py --tag v0.26.3 --dir releases/v0.26.3 --no-latest
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO = "lzyr9010/hongguoTV-updates"
API = "https://api.cnb.cool"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def token():
    raw = open(os.path.join(ROOT, ".cnb-credentials"), encoding="utf-8").read()
    m = re.search(r"://[^:]+:([^@]+)@", raw)
    if not m:
        sys.exit("cannot read token from .cnb-credentials")
    return m.group(1)


PAT = token()


def call(method, url, body=None, content_type="application/json", timeout=60):
    data = body
    if body is not None and not isinstance(body, (bytes, bytearray)):
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + PAT)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            return resp.status, payload
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def release_id(tag, title=None, notes=None):
    status, payload = call("GET", f"{API}/{REPO}/-/releases/tags/{tag}")
    if status == 200:
        return json.loads(payload)["id"]
    if status != 404:
        sys.exit(f"release {tag}: {status} {payload[:200]}")
    # 不存在就建一个（指向 main），之后统一由附件上传流程补齐
    status, payload = call("POST", f"{API}/{REPO}/-/releases", {
        "tag_name": tag, "name": title or tag, "body": notes or "",
        "target_commitish": "main", "prerelease": True, "make_latest": "false",
    })
    if status not in (200, 201):
        sys.exit(f"create release {tag}: {status} {payload[:200]}")
    print(f"created release {tag}")
    return json.loads(payload)["id"]


def upload(release, path):
    name = os.path.basename(path)
    data = open(path, "rb").read()
    status, payload = call(
        "POST",
        f"{API}/{REPO}/-/releases/{release}/asset-upload-url",
        {"asset_name": name, "overwrite": True, "size": len(data)},
    )
    if status not in (200, 201):
        print(f"  ! {name}: upload-url {status} {payload[:160]}")
        return False
    info = json.loads(payload)
    put = urllib.request.Request(info["upload_url"], data=data, method="PUT")
    put.add_header("Content-Type", "application/octet-stream")
    try:
        with urllib.request.urlopen(put, timeout=600) as resp:
            if resp.status not in (200, 201):
                print(f"  ! {name}: put {resp.status}")
                return False
    except urllib.error.HTTPError as e:
        print(f"  ! {name}: put {e.code} {e.read()[:160]}")
        return False
    status, payload = call("POST", info["verify_url"], b"")
    if status != 200:
        print(f"  ! {name}: verify {status} {payload[:160]}")
        return False
    print(f"  + {name} ({len(data)/1048576:.1f} MB)")
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--dir", required=True)
    ap.add_argument("--no-latest", action="store_true")
    ap.add_argument("--title")
    ap.add_argument("--notes", default="")
    args = ap.parse_args()

    rid = release_id(args.tag, args.title, args.notes)
    print(f"release {args.tag} -> {rid}")
    files = sorted(
        os.path.join(args.dir, f)
        for f in os.listdir(args.dir)
        if os.path.isfile(os.path.join(args.dir, f))
    )
    ok = 0
    for path in files:
        if upload(rid, path):
            ok += 1
    print(f"uploaded {ok}/{len(files)}")

    if not args.no_latest:
        status, payload = call(
            "PATCH",
            f"{API}/{REPO}/-/releases/{rid}",
            {"prerelease": False, "make_latest": "true"},
        )
        print("set latest:", status, payload[:120])
    sys.exit(0 if ok == len(files) else 1)


if __name__ == "__main__":
    main()
