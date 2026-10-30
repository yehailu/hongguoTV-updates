#!/usr/bin/env python3
"""Target-scoped Git transport using the user's CNB token, without saving credentials.
Read operations are default. Push requires --allow-push and never permits force-push.
"""
import argparse
import base64
import os
import subprocess
import sys
import winreg
from pathlib import Path
from urllib.parse import urlsplit

ROOT=Path(__file__).resolve().parents[1]
GIT=Path(r'D:\DevTools\Git\cmd\git.exe')
REPO='lzyr9010/hongguoTV-updates'

def token_from_user():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER,'Environment') as key:
        token,_=winreg.QueryValueEx(key,'CNB_API_TOKEN')
    if not isinstance(token,str) or not token.strip(): raise RuntimeError('CNB_API_TOKEN is unavailable')
    return token.strip()

def configured_origin():
    origin=subprocess.run([str(GIT),'remote','get-url','origin'],cwd=ROOT,check=True,stdout=subprocess.PIPE,text=True).stdout.strip()
    parsed=urlsplit(origin)
    if parsed.scheme!='https' or parsed.hostname!='cnb.cool' or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise RuntimeError('Origin must be a credential-free HTTPS CNB URL')
    if parsed.path.rstrip('/').removesuffix('.git')!='/'+REPO: raise RuntimeError('Origin is not the authorized repository')
    return origin

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--allow-push',action='store_true')
    parser.add_argument('operation',choices=['ls-remote','fetch','push','scan-staged'])
    args=parser.parse_args()
    configured_origin()
    token=token_from_user()
    basic=base64.b64encode(('cnb:'+token).encode()).decode()
    env=os.environ.copy()
    for key in ('GIT_SSL_NO_VERIFY','GIT_CURL_VERBOSE','GIT_TRACE_PACKET'):
        env.pop(key,None)
    env.update({'GIT_CONFIG_COUNT':'3','GIT_CONFIG_KEY_0':'credential.helper','GIT_CONFIG_VALUE_0':'',
        'GIT_CONFIG_KEY_1':'http.https://cnb.cool/.extraheader','GIT_CONFIG_VALUE_1':'Authorization: Basic '+basic,
        'GIT_CONFIG_KEY_2':'http.sslVerify','GIT_CONFIG_VALUE_2':'true',
        'GIT_TERMINAL_PROMPT':'0','GCM_INTERACTIVE':'Never','GIT_TRACE':'0','GIT_TRACE_CURL':'0','GIT_TRACE_REDACT':'1',
        'GCM_TRACE':'0','GCM_TRACE_SECRETS':'0'})
    if args.operation=='scan-staged':
        names=subprocess.run([str(GIT),'diff','--cached','--name-only','-z'],cwd=ROOT,check=True,stdout=subprocess.PIPE).stdout.split(b'\0')
        for name in filter(None,names):
            file=name.decode('utf-8')
            if Path(file).suffix.lower() in ('.jks','.keystore') or '.cnb-credentials' in file or '.dsh-project-memory' in file:
                raise RuntimeError('Private file may not be committed: '+file)
            blob=subprocess.run([str(GIT),'show',':'+file],cwd=ROOT,check=True,stdout=subprocess.PIPE).stdout
            if token.encode() in blob or basic.encode() in blob: raise RuntimeError('Credential found in staged content: '+file)
        print('Staged files passed secret-value and private-file checks')
        return
    if args.operation=='push':
        if not args.allow_push: raise RuntimeError('Push needs explicit --allow-push')
        command=['push','origin','HEAD:refs/heads/main']
    elif args.operation=='fetch': command=['fetch','origin','main']
    else: command=['ls-remote','origin','refs/heads/main']
    result=subprocess.run([str(GIT),*command],cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    safe=result.stdout.replace(token,'[REDACTED]').replace(basic,'[REDACTED]')
    print(safe,end='')
    sys.exit(result.returncode)

if __name__=='__main__': main()
