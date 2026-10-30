#!/usr/bin/env python3
"""Generate an isolated Android 4.1+ (API16) hardware-only project from current sources.
Never edits source MainActivity/Library or changes modern dependencies. Repeat after main-agent edits.
Usage: python tools/build_android41.py [--output build/android41/kotlin-tv] [--check]
Then in output: gradlew.bat :app:assembleHwDebug :app:lintHwDebug -PvariantId=a41
"""
from pathlib import Path
import argparse
import json
import re
import shutil
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'kotlin-tv'
TEMPLATES = ROOT / 'tools/android41'


def replace_required(text, before, after, name):
    if before not in text:
        raise RuntimeError(f'{name}: expected source fragment missing; converter must be reviewed: {before[:100]}')
    return text.replace(before, after)


def block(text, anchor):
    start = text.index(anchor)
    opening = text.index('{', start)
    depth = 0
    # All selected blocks have no braces in string literals.
    for end in range(opening, len(text)):
        if text[end] == '{': depth += 1
        elif text[end] == '}':
            depth -= 1
            if depth == 0: return start, end + 1
    raise RuntimeError('unterminated block: ' + anchor)


def main_compat(text):
    text = replace_required(text, 'class MainActivity: Activity() {', 'class MainActivity: Activity() {\n    private var legacyDestroyed=false', 'MainActivity lifecycle')
    text = re.sub(r'\bisDestroyed\b', '(legacyDestroyed || isFinishing)', text)
    text = replace_required(text, 'override fun onDestroy() {', 'override fun onDestroy() {\n        legacyDestroyed=true', 'MainActivity destruction')
    text = replace_required(text, '        super.onCreate(state)', '        super.onCreate(state)\n        if(BuildConfig.DEBUG && intent.getBooleanExtra("android41Smoke",false)) LegacyDiagnostics.start(this)', 'MainActivity debug diagnostics')
    start, end = block(text, '    private val networkCallback=object: ConnectivityManager.NetworkCallback()')
    text = text[:start] + '''    private val networkCallback=LegacyNetworkMonitor(this) {
        main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() }
    }''' + text[end:]
    registration = re.compile(r'        runCatching \{\s*if\(android\.os\.Build\.VERSION\.SDK_INT>=24\).*?networkRegistered=true\s*\}', re.S)
    text, count = registration.subn('        runCatching { networkCallback.start(); networkRegistered=true }', text, count=1)
    if count != 1: raise RuntimeError('MainActivity network registration changed; review converter')
    text = replace_required(text, 'if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)', 'if(networkRegistered) networkCallback.close()', 'MainActivity network disposal')
    text = replace_required(text, 'newBase.createConfigurationContext(config)', 'LegacyPlatform.scaledContext(newBase,config)', 'MainActivity font scale')
    text = text.replace('android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos', 'LegacyPlatform.codecs()')
    # Framework PlaybackState fields are compile-time ints; use compat names explicitly for clarity.
    text = text.replace('android.media.session.PlaybackState.', 'android.support.v4.media.session.PlaybackStateCompat.')
    text = text.replace('View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY', '(if(android.os.Build.VERSION.SDK_INT>=19) View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0)')
    return text


def write_icon(path):
    # Native PNG instead of unsupported pre-API21 framework vector drawables. No Pillow dependency.
    n = 108
    rows = []
    for y in range(n):
        row = bytearray([0])
        for x in range(n):
            # Familiar coral/white heart and play silhouette.
            X=(x-54)/36; Y=-(y-58)/34
            heart=(X*X+Y*Y-1)**3-X*X*Y**3 <= 0
            color=(255,255,255,255) if heart else (255,105,79,255)
            if 45<=x<=68 and abs(y-56)<=((68-x)*14/23): color=(255,105,79,255)
            if 52<=x<=78 and 9<=y<=27 and ((x-64)/15)**2+((y-17)/10)**2<1: color=(63,164,91,255)
            row.extend(color)
        rows.append(bytes(row))
    def chunk(name, data): return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',n,n,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows)))+chunk(b'IEND',b''))


def generate(output):
    output = output.resolve()
    if output == SOURCE.resolve() or SOURCE.resolve() in output.parents or output in SOURCE.resolve().parents:
        raise RuntimeError('output must be separate from the modern source tree')
    marker = output / '.android41-generated'
    if output.exists() and not marker.exists():
        raise RuntimeError(f'refusing to overwrite unmarked existing project: {output}')
    output.mkdir(parents=True, exist_ok=True)
    marker.write_text('Generated by tools/build_android41.py; source remains untouched.\n', encoding='utf-8')
    # Exclude generated data, credentials, and software decoder flavor altogether.
    shutil.copytree(SOURCE, output, dirs_exist_ok=True, ignore=shutil.ignore_patterns('build','.gradle','.kotlin','local.properties','keystore.properties','*.jks','*.keystore','distribution','sw'))
    for path in output.glob('**/*.kt'):
        if '/build/' in path.relative_to(output).as_posix(): continue
        text = path.read_text(encoding='utf-8')
        if path.name == 'MainActivity.kt': text = main_compat(text)
        text = text.replace('androidx.media3.common.util.', 'com.google.android.exoplayer2.util.')
        text = text.replace('androidx.media3.common.', 'com.google.android.exoplayer2.')
        text = text.replace('androidx.media3.exoplayer.', 'com.google.android.exoplayer2.')
        text = text.replace('androidx.media3.datasource.', 'com.google.android.exoplayer2.upstream.')
        text = text.replace('androidx.media3.ui.', 'com.google.android.exoplayer2.ui.')
        text = re.sub(r'^@androidx\.annotation\.OptIn\(com\.google\.android\.exoplayer2\.util\.UnstableApi::class\)\s*\n','',text,flags=re.M)
        if path.name == 'MainActivity.kt':
            text = text.replace('import com.google.android.exoplayer2.*','import com.google.android.exoplayer2.*\nimport com.google.android.exoplayer2.audio.AudioAttributes\nimport com.google.android.exoplayer2.util.MimeTypes')
        text = text.replace('okhttp3.RequestBody.Companion.toRequestBody','com.hongguotv.core.toRequestBody')
        text = text.replace('okhttp3.MediaType.Companion.toMediaType','com.hongguotv.core.toMediaType')
        text = text.replace('okhttp3.ResponseBody.Companion.toResponseBody','com.hongguotv.core.toResponseBody')
        if 'package com.hongguotv.nativeapp' in text:
            text = text.replace('package com.hongguotv.nativeapp','package com.hongguotv.nativeapp\n\nimport com.hongguotv.core.*',1)
        text = text.replace('View.generateViewId()', 'LegacyPlatform.generateViewId()')
        text = text.replace('.isAttachedToWindow', '.legacyAttached')
        text = text.replace('.isLaidOut', '.legacyLaidOut')
        text = text.replace('progressTintList=android.content.res.ColorStateList.valueOf(accent)','LegacyPlatform.tintProgress(this,accent,false)')
        text = text.replace('indeterminateTintList=android.content.res.ColorStateList.valueOf(accent)','LegacyPlatform.tintProgress(this,accent,true)')
        text = re.sub(r'canvas\.drawRoundRect\(left, cy - trackH / 2, (right|fillRight), cy \+ trackH / 2, trackH / 2, trackH / 2, (paintTrack|paintFill)\)',r'canvas.drawRoundRect(android.graphics.RectF(left, cy - trackH / 2, \1, cy + trackH / 2), trackH / 2, trackH / 2, \2)',text)
        if path.name == 'ArtworkLoader.kt':
            text = replace_required(text,'(context.applicationContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true','LegacyPlatform.lowRam(context.applicationContext)','ArtworkLoader memory')
            text = text.replace('value.allocationByteCount','LegacyPlatform.bitmapBytes(value)')
            text = text.replace('cache.trimToSize(cacheBytes / 2)','if(android.os.Build.VERSION.SDK_INT>=17) cache.trimToSize(cacheBytes / 2) else cache.evictAll()')
        if path.name == 'TvTools.kt':
            # Framework Button tint has no implementation below API21. Explicit state background works everywhere.
            text = re.sub(r'backgroundTintList=android\.content\.res\.ColorStateList\([^\n]+', 'background=android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused),TvStyle.shape(activity,TvStyle.accent)); addState(intArrayOf(),TvStyle.shape(activity,TvStyle.keypad)) }', text)
        if path.name == 'TvMediaSession.kt':
            text = text.replace('android.media.MediaMetadata','android.support.v4.media.MediaMetadataCompat')
            text = text.replace('android.media.session.MediaSession','android.support.v4.media.session.MediaSessionCompat')
            text = text.replace('android.media.session.PlaybackState','android.support.v4.media.session.PlaybackStateCompat')
            text = re.sub(r'\bMediaMetadata\b','MediaMetadataCompat',text)
            text = re.sub(r'\bMediaSession\b','MediaSessionCompat',text)
            text = re.sub(r'\bPlaybackState\b','PlaybackStateCompat',text)
            text = text.replace('    private var metadataKey=""','    init { LegacyMediaButtons.session=session }\n    private var metadataKey=""')
            text = text.replace('fun release() { session.release() }','fun release() { if(LegacyMediaButtons.session===session) LegacyMediaButtons.session=null; session.release() }')
        text = text.replace('.keySet()', '.keys().asSequence().toSet()')
        path.write_text(text,encoding='utf-8')
    app = output/'app/build.gradle.kts'
    text = app.read_text(encoding='utf-8')
    text = re.sub(r'val minSdkVersion = .*?\?\.toIntOrNull\(\) \?: \d+', 'val minSdkVersion = 16', text, count=1, flags=re.S)
    if 'buildToolsVersion =' not in text:
        text = text.replace('compileSdk = 36','compileSdk = 36\n    buildToolsVersion = "36.1.0"')
    text = text.replace('targetSdk = 36','targetSdk = 28\n        multiDexEnabled = true')
    text = text.replace('"hw-phone"','"a41"')
    start, end = block(text, '        create("sw")')
    text = text[:start] + text[end:]
    text = re.sub(r'^.*"swImplementation".*\n','',text,flags=re.M)
    text = re.sub(r'^.*implementation\("androidx.core:core:1.13.1"\).*\n','',text,flags=re.M)
    text = text.replace('androidx.recyclerview:recyclerview:1.3.2','androidx.recyclerview:recyclerview:1.2.1')
    text = text.replace('com.google.zxing:core:3.5.3','com.google.zxing:core:3.3.3')
    text = text.replace('desugar_jdk_libs_nio:2.1.5','desugar_jdk_libs:2.1.5')
    text = text.replace('com.squareup.okhttp3:okhttp:4.12.0','com.squareup.okhttp3:okhttp:3.12.13')
    text = text.replace('androidx.media3:media3-exoplayer:1.11.1','com.google.android.exoplayer:exoplayer-core:2.18.7')
    text = text.replace('androidx.media3:media3-ui:1.11.1','com.google.android.exoplayer:exoplayer-ui:2.18.7')
    text = text.replace('buildFeatures { buildConfig = true }','buildFeatures { buildConfig = true }\n    // This legacy APK is sideload-only, not a Google Play submission. Keep all NewApi errors enabled.\n    lint { warning += "ExpiredTargetSdkVersion" }')
    text = text.replace('dependencies {','dependencies {\n    testImplementation("junit:junit:4.13.2")\n    implementation("androidx.multidex:multidex:2.0.1")\n    implementation("androidx.media:media:1.6.0")',1)
    app.write_text(text,encoding='utf-8')
    core = output/'core/build.gradle.kts'
    text = core.read_text(encoding='utf-8').replace('com.squareup.okhttp3:okhttp:4.12.0','com.squareup.okhttp3:okhttp:3.12.13').replace('bcprov-jdk18on:1.79','bcprov-jdk15to18:1.79')
    # Keep jsoup's current parsing behavior; desugar Java8 APIs and audit lint output.
    core.write_text(text,encoding='utf-8')
    appsrc = output/'app/src/main/kotlin/com/hongguotv/nativeapp'
    shutil.copyfile(TEMPLATES/'LegacyPlatform.kt',appsrc/'LegacyPlatform.kt')
    shutil.copyfile(TEMPLATES/'LegacyMediaButtonReceiver.kt',appsrc/'LegacyMediaButtonReceiver.kt')
    shutil.copyfile(TEMPLATES/'LegacyDiagnostics.kt',appsrc/'LegacyDiagnostics.kt')
    testsrc = output/'app/src/test/kotlin/com/hongguotv/nativeapp'
    testsrc.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(TEMPLATES/'TlsSupportTest.kt',testsrc/'TlsSupportTest.kt')
    testcerts=output/'app/src/test/resources/certs'
    testcerts.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(output/'app/src/main/assets/certs/isrg_root_x1.pem',testcerts/'isrg_root_x1.pem')
    shutil.copyfile(TEMPLATES/'OkHttpLegacy.kt',output/'core/src/main/kotlin/com/hongguotv/core/OkHttpLegacy.kt')
    manifest = output/'app/src/main/AndroidManifest.xml'
    text = manifest.read_text(encoding='utf-8').replace('<application ', '<application android:name="androidx.multidex.MultiDexApplication" ',1)
    text = text.replace('<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>','<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>\n    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="18"/>')
    text = text.replace('<provider ', '<receiver android:name=".LegacyMediaButtonReceiver" android:exported="false"><intent-filter><action android:name="android.intent.action.MEDIA_BUTTON"/></intent-filter></receiver>\n        <provider ',1)
    manifest.write_text(text,encoding='utf-8')
    styles = output/'app/src/main/res/values/styles.xml'
    modernstyles = styles.read_text(encoding='utf-8')
    (output/'app/src/main/res/values-v21').mkdir(exist_ok=True)
    (output/'app/src/main/res/values-v21/styles.xml').write_text(modernstyles,encoding='utf-8')
    styles.write_text('''<resources>
<style name="TvDialog" parent="android:style/Theme.Holo.Dialog"><item name="android:windowBackground">@drawable/tv_dialog_panel</item><item name="android:textColorPrimary">#F3F4F6</item><item name="android:textColorSecondary">#C2CBD8</item><item name="android:backgroundDimAmount">0.78</item></style>
<style name="AppTheme" parent="android:style/Theme.Holo.NoActionBar"><item name="android:fontFamily">sans</item><item name="android:windowActionModeOverlay">true</item><item name="android:alertDialogTheme">@style/TvDialog</item><item name="android:windowBackground">#0B0F15</item><item name="android:windowNoTitle">true</item></style>
</resources>''',encoding='utf-8')
    # Qualify the modern vector; base drawable is a generated PNG.
    icon = output/'app/src/main/res/drawable/app_icon.xml'
    (output/'app/src/main/res/drawable-v21').mkdir(exist_ok=True)
    shutil.copyfile(icon,output/'app/src/main/res/drawable-v21/app_icon.xml')
    # Verified absolute path belongs to this marked output, not the source tree.
    if icon.resolve().parent != (output/'app/src/main/res/drawable').resolve(): raise RuntimeError('unsafe icon path')
    icon.unlink()
    write_icon(output/'app/src/main/res/drawable/app_icon.png')
    shutil.copyfile(TEMPLATES/'LegacyApkInstaller.kt',appsrc/'LegacyApkInstaller.kt')
    updater = appsrc/'UpdateCenter.kt'
    text = updater.read_text(encoding='utf-8')
    start, end = block(text,'    fun install(context: Context, apk: File): Boolean')
    text = text[:start]+'    fun install(context: Context, apk: File): Boolean = LegacyApkInstaller.install(context,apk)'+text[end:]
    updater.write_text(text,encoding='utf-8')
    report={'minSdk':16,'targetSdk':28,'variant':'a41','softwareDecoder':False,'source':str(SOURCE),'output':str(output),'dependencies':{'exoplayer':'2.18.7','okhttp':'3.12.13','okio':'1.15.0 (transitive)','multidex':'2.0.1','core':'1.8.0','recyclerview':'1.2.1','media':'1.6.0','zxing':'3.3.3','bouncycastle':'jdk15to18:1.79'}}
    (output/'android41-conversion.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return output


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'build/android41/kotlin-tv')
    parser.add_argument('--check',action='store_true',help='Check no modern playback imports or unguarded known old-API call sites remain')
    args=parser.parse_args()
    output=generate(args.output)
    if args.check:
        for path in (output/'app/src/main/kotlin').rglob('*.kt'):
            text=path.read_text(encoding='utf-8')
            for forbidden in ('androidx.media3','View.generateViewId()','.isAttachedToWindow','MediaCodecList.REGULAR_CODECS','ConnectivityManager.NetworkCallback'):
                if forbidden in text: raise RuntimeError(f'{path}: unconverted {forbidden}')
        print('Known API16 source compatibility checks passed; compilation/device tests are still required.')
