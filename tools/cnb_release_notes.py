#!/usr/bin/env python3
"""把 CNB Release 说明精简为「实现了什么」，去掉实现细节。"""
import json, re, os, urllib.request, urllib.error

REPO = "lzyr9010/hongguoTV-updates"
API = "https://api.cnb.cool"
cred = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".cnb-credentials"), encoding="utf-8").read()
PAT = re.search(r"://[^:]+:([^@]+)@", cred).group(1)

NEW = {
    "v0.26.6": "手机 / 车机播放页支持点按调出菜单与选集，新增「返回选集」；TV 交互不变。",
    "v0.26.5": "修复同系列各季显示不全，详情页「其他季」显示全部季数。",
    "v0.26.3": "排行榜改为统一「红果热播榜」。",
    "v0.26.2": "修复站点偶发超时，数据源接入自动化自检。",
    "v0.26.1": "修复播放与排行榜、剧场偶发失败。",
    "v0.26.0": "修复搜索与片源失效。",
    "v0.25.0": "新增应用内更新。",
    "v0.24.2": "维护版，只保留 4 个安装包。",
}

def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + PAT)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

status, payload = call("GET", f"{API}/{REPO}/-/releases")
rels = json.loads(payload)
for tag, text in NEW.items():
    r = next((x for x in rels if x.get("tag_name") == tag), None)
    if not r:
        print("skip", tag, "not found")
        continue
    rid = r["id"]
    s, p = call("PATCH", f"{API}/{REPO}/-/releases/{rid}", {"body": text})
    print(tag, s, ("" if s == 200 else p[:120]))
