"""Summarize JUnit results and hash the local APK delivery (no remote publishing)."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,default=ROOT/'outputs/optimized-android')
args=parser.parse_args()

def suite_summary(folder):
    files=sorted(folder.glob('TEST-*.xml'))
    if not files: raise SystemExit('JUnit results are missing: '+str(folder))
    totals={'suites':len(files),'tests':0,'failures':0,'errors':0,'skipped':0}
    for path in files:
        suite=ET.parse(path).getroot()
        for key in ('tests','failures','errors','skipped'):
            totals[key]+=int(suite.attrib.get(key,'0'))
    if totals['failures'] or totals['errors']:
        raise SystemExit('Verification results contain failures: '+json.dumps(totals))
    return totals

verification={
    'modernCore':suite_summary(ROOT/'kotlin-tv/core/build/test-results/test'),
    'android41Core':suite_summary(ROOT/'build/android41/kotlin-tv/core/build/test-results/test'),
    'android41Tls':suite_summary(ROOT/'build/android41/kotlin-tv/app/build/test-results/testHwDebugUnitTest'),
    'scope':'Local debug-signed verification builds; not a published release or universal device certification.',
    'android41Limits':['API16+ only; Android4.0 not supported','Hardware codecs required; no FFmpeg video fallback'],
    'observedModernTimingsMs':{'episodePage':[883,797],'uncachedInitialFirstFrame':[4116],'cachedSwitchFirstFrame':[345,253]},
    'timingCaveat':'One API36 emulator, one live 70-episode series; different episodes, not a controlled A/B benchmark.',
    'apks':[],
}
args.output.mkdir(parents=True,exist_ok=True)
lines=[]
for path in sorted(args.output.glob('*.apk')):
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    verification['apks'].append({'file':path.name,'bytes':path.stat().st_size,'sha256':digest})
    lines.append(digest+'  '+path.name)
if len(lines)!=3: raise SystemExit('Expected exactly three final APK deliverables, got '+str(len(lines)))
(args.output/'verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(args.output/'SHA256SUMS.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(verification,ensure_ascii=False,indent=2))
