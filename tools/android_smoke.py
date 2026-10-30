"""ADB UI inspection helper for local emulator verification (no device data deletion)."""
import argparse
import json
import re
import subprocess
from pathlib import Path
from xml.etree import ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--adb',default=r'D:\Android\Sdk\platform-tools\adb.exe')
parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--click-text')
parser.add_argument('--click-desc')
args=parser.parse_args()
args.output.mkdir(parents=True,exist_ok=True)

def adb(*command):
    subprocess.run([args.adb,'-s',args.serial,*command],check=True,stdout=subprocess.DEVNULL)

remote='/sdcard/hgtv-agent-smoke.xml'
adb('shell','uiautomator','dump',remote)
local=args.output/'ui.xml'
adb('pull',remote,str(local))
nodes=[]
for node in ET.parse(local).getroot().iter('node'):
    item={k:node.attrib.get(k,'') for k in ('text','resource-id','class','content-desc','clickable','focused','bounds')}
    nodes.append(item)
(args.output/'ui-nodes.json').write_text(json.dumps(nodes,ensure_ascii=False,indent=2),encoding='utf-8')
if args.click_text is not None or args.click_desc is not None:
    candidates=[n for n in nodes if (args.click_text is not None and n['text']==args.click_text) or (args.click_desc is not None and n['content-desc']==args.click_desc)]
    if not candidates:
        raise SystemExit('Requested UI target not found')
    target=candidates[0]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',target['bounds']))
    if x2<=x1 or y2<=y1: raise SystemExit('UI target has no visible bounds')
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    print(json.dumps({'clicked':target},ensure_ascii=False))
else:
    print(json.dumps({'nodes':len(nodes),'output':str(args.output/'ui-nodes.json')},ensure_ascii=False))
