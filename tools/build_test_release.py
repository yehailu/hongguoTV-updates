#!/usr/bin/env python3
"""Build-only test release. Does not publish, mutate CNB, or change global configuration."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
TEST_SIGNER='a79eaa62168e8171efc6f7b9760514f2cb8da4a3f68d62ec10506aec197a7552'
ORIGINAL_SIGNER='916a5d274a41d7e536bceb3f14a1195cb6979f9ddbf771fd7fe7ddd3d091c6a0'

def sha256(path):
    digest=hashlib.sha256()
    with path.open('rb') as source:
        for part in iter(lambda:source.read(1024*1024),b''): digest.update(part)
    return digest.hexdigest()

def executable(command):
    if os.name=='nt' and Path(command[0]).suffix.lower() in ('.bat','.cmd'):
        return ['cmd.exe','/d','/c',*map(str,command)]
    return list(map(str,command))

def run(command,env,capture=False):
    return subprocess.run(executable(command),check=True,env=env,cwd=ROOT,text=True,encoding='utf-8',errors='replace',
        stdout=subprocess.PIPE if capture else None).stdout

def build(version,code,out):
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]{0,63}',version) or '..' in version or code<54:
        raise ValueError('Invalid test version or non-increasing code')
    env=os.environ.copy()
    java=Path(env.get('JAVA_HOME',''))
    sdk=Path(env.get('ANDROID_HOME',''))
    if not (java/'bin/java.exe').is_file(): raise ValueError('Set JAVA_HOME to JDK17')
    aapt=sdk/'build-tools/36.1.0/aapt.exe'
    signer=sdk/'build-tools/36.1.0/apksigner.bat'
    if not aapt.is_file() or not signer.is_file(): raise ValueError('Android Build Tools36.1.0 are required')
    env['HONGGUOTV_VERSION_NAME']=version; env['HONGGUOTV_VERSION_CODE']=str(code)
    for key in ('HONGGUOTV_MIN_SDK','HONGGUOTV_SCREEN_ORIENTATION','HONGGUOTV_PKG_SUFFIX'): env.pop(key,None)
    env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' -Djava.net.preferIPv4Stack=true').strip()
    out.mkdir(parents=True,exist_ok=True)
    variants={}; checks={}
    kotlin=ROOT/'kotlin-tv'
    def gradle(project,*tasks): run([project/'gradlew.bat','-p',project,*tasks,'--console=plain'],env)
    def collect(key,source,min_sdk):
        target=out/f'hongguotv-{version}-{key}-debug.apk'
        shutil.copyfile(source,target)
        badging=run([aapt,'dump','badging',target],env,capture=True)
        match=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
        actual_min=re.search(r"^sdkVersion:'(\d+)'",badging,re.M)
        actual_target=re.search(r"^targetSdkVersion:'(\d+)'",badging,re.M)
        if not match or match.groups()!=('com.hongguotv.nativeapp',str(code),version) or not actual_min or int(actual_min[1])!=min_sdk:
            raise ValueError('APK package/version/minSdk mismatch: '+key)
        cert_output=run([signer,'verify','--print-certs',target],env,capture=True)
        cert=re.search(r'certificate SHA-256 digest:\s*([a-f0-9]{64})',cert_output,re.I)
        if not cert or cert[1].lower()!=TEST_SIGNER: raise ValueError('Unexpected test signing identity: '+key)
        variants[key]={'versionCode':code,'versionName':version,'file':target.name,'size':target.stat().st_size,'sha256':sha256(target),'minSdk':min_sdk}
        checks[key]={'packageName':'com.hongguotv.nativeapp','minSdk':min_sdk,'targetSdk':int(actual_target[1]) if actual_target else None,
            'versionCode':code,'versionName':version,'certificateSha256':cert[1].lower()}
        print('Collected validated variant: '+key,flush=True)
    gradle(kotlin,':core:test',':app:assembleSwDebug',':app:lintSwDebug','-PminSdk=27','-PvariantId=sw-tv')
    collect('sw-tv',kotlin/'app/build/outputs/apk/sw/debug/app-sw-debug.apk',27)
    gradle(kotlin,':app:assembleSwDebug',':app:lintSwDebug','-PminSdk=24','-PvariantId=sw-a7')
    collect('sw-a7',kotlin/'app/build/outputs/apk/sw/debug/app-sw-debug.apk',24)
    gradle(kotlin,':app:assembleHwDebug',':app:lintHwDebug','-PminSdk=26','-PvariantId=hw-phone','-PscreenOrientation=unspecified')
    collect('hw-phone',kotlin/'app/build/outputs/apk/hw/debug/app-hw-debug.apk',26)
    legacy=ROOT/'build/android41-test/kotlin-tv'
    run([sys.executable,ROOT/'tools/build_android41.py','--output',legacy,'--check'],env)
    gradle(legacy,':core:test',':app:assembleHwDebug',':app:lintHwDebug',':app:testHwDebugUnitTest','-PvariantId=a41')
    collect('a41',legacy/'app/build/outputs/apk/hw/debug/app-hw-debug.apk',16)
    notes='测试版：选集先显示分集、后续集开头预缓存可调0–3集、Android4.1适配。与CNB原版签名不同，原版不能直接覆盖：请先备份收藏/进度，再卸载原版手动安装。本版不设latest，不影响稳定自动更新。Android4.0不支持，旧机仍需相应硬件解码器。'
    manifest={'versionCode':code,'versionName':version,'notes':notes,'prerelease':True,'packageName':'com.hongguotv.nativeapp','variants':variants}
    (out/'update.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    record={'versionName':version,'versionCode':code,'prerelease':True,'stableLatestChangeAllowed':False,
        'originalCertificateSha256':ORIGINAL_SIGNER,'testCertificateSha256':TEST_SIGNER,'variants':checks,
        'scope':'Build, signature and lint checks. Device/visual coverage remains limited. Android5 stable branch is unchanged.'}
    (out/'verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (out/'SHA256SUMS.txt').write_text(''.join(f"{entry['sha256']}  {entry['file']}\n" for entry in variants.values()),encoding='utf-8')
    print('Test build ready (not published): '+str(out),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--version',default='0.27.0-test.1')
    parser.add_argument('--code',type=int,default=54)
    parser.add_argument('--out',type=Path)
    args=parser.parse_args()
    build(args.version,args.code,(args.out or ROOT/f'releases/v{args.version}').resolve())
