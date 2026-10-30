#!/usr/bin/env python3
"""Read-only stdio bridge to the user's existing CNB MCP server.
Reads CNB_API_TOKEN from Windows User Environment only in memory; never persists it.
No commit, release, delete, merge, or build-trigger tools are exposed by this helper.
"""
import argparse
import json
import os
import queue
import re
import subprocess
import threading
import winreg
from pathlib import Path

READ_ONLY_TOOLS={'cnb_get_repository','cnb_get_current_repository','cnb_list_repositories','cnb_list_group_repositories'}

def user_token():
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER,'Environment') as key:
        token,_=winreg.QueryValueEx(key,'CNB_API_TOKEN')
    if not isinstance(token,str) or not token.strip():
        raise RuntimeError('CNB_API_TOKEN is missing from Windows user environment')
    return token.strip()

class CnbMcpClient:
    def __init__(self,node,entrypoint):
        self.token=user_token()
        env=os.environ.copy()
        env['API_TOKEN']=self.token
        env['API_BASE_URL']='https://api.cnb.cool'
        env['DOTENV_CONFIG_QUIET']='true'
        self.process=subprocess.Popen([str(node),str(entrypoint)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,text=True,encoding='utf-8',errors='replace',env=env,bufsize=1)
        self.messages=queue.Queue(); self.serial=0
        threading.Thread(target=self._read,daemon=True).start()
        threading.Thread(target=self._discard_errors,daemon=True).start()
        try:
            self.server=self.request('initialize',{'protocolVersion':'2024-11-05','capabilities':{},
                'clientInfo':{'name':'hgtv-cnb-preflight','version':'1.0'}})
            self.notify('notifications/initialized',{})
        except Exception:
            self.close()
            raise
    def _read(self):
        for line in self.process.stdout:
            try: self.messages.put(json.loads(line))
            except json.JSONDecodeError: pass # Ignore dotenv/startup noise; never print raw stdout.
        self.messages.put({'closed':True})
    def _discard_errors(self):
        for _ in self.process.stderr: pass # Never forward server diagnostics containing request configuration.
    def sanitize(self,value):
        if isinstance(value,dict):
            return {k:('[REDACTED]' if re.search(r'(?i)token|password|secret|authorization|credential',k) else self.sanitize(v)) for k,v in value.items()}
        if isinstance(value,list): return [self.sanitize(v) for v in value]
        if isinstance(value,str):
            value=value.replace(self.token,'[REDACTED]')
            value=re.sub(r'(?i)Bearer\s+[A-Za-z0-9._~+/=-]+','Bearer [REDACTED]',value)
            return re.sub(r'(https?://)[^/@\s]+:[^/@\s]+@',r'\1[REDACTED]@',value)
        return value
    def notify(self,method,params):
        self.process.stdin.write(json.dumps({'jsonrpc':'2.0','method':method,'params':params})+'\n'); self.process.stdin.flush()
    def request(self,method,params,timeout=45):
        self.serial+=1; identity=self.serial
        self.process.stdin.write(json.dumps({'jsonrpc':'2.0','id':identity,'method':method,'params':params})+'\n'); self.process.stdin.flush()
        while True:
            response=self.messages.get(timeout=timeout)
            if response.get('closed'): raise RuntimeError('CNB MCP server exited before replying')
            if response.get('id')!=identity: continue
            if 'error' in response: raise RuntimeError(json.dumps(self.sanitize(response['error']),ensure_ascii=False))
            return response.get('result',{})
    def tools(self): return self.request('tools/list',{}).get('tools',[])
    def call_read_only(self,name,arguments):
        if name not in READ_ONLY_TOOLS: raise ValueError('Only explicit read-only CNB preflight tools are allowed')
        result=self.request('tools/call',{'name':name,'arguments':arguments})
        if result.get('isError'): raise RuntimeError(json.dumps(self.sanitize(result),ensure_ascii=False))
        return result
    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=5)
        self.token=''

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--node',type=Path,default=Path(r'D:\DevTools\nodejs\node.exe'))
    parser.add_argument('--entrypoint',type=Path,default=Path(r'D:\DevTools\npm-global\node_modules\@cnbcool\mcp-server\dist\stdio.js'))
    parser.add_argument('--repo',default='lzyr9010/hongguoTV-updates')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    client=CnbMcpClient(args.node,args.entrypoint)
    try:
        tools=client.tools()
        result=client.call_read_only('cnb_get_repository',{'repo':args.repo})
        content=[v.get('text','') for v in result.get('content',[]) if v.get('type')=='text']
        repository=json.loads(content[0]) if content else {}
        output={'connected':True,'server':client.server.get('serverInfo',{}),'tool_count':len(tools),
            'tool_names':[v['name'] for v in tools], 'repository':client.sanitize(repository),
            'release_upload_tool_available':any('release' in v['name'].lower() for v in tools),
            'source_commit_tool_available':any('commit' in v['name'].lower() or 'push' in v['name'].lower() for v in tools),
            'mutations_performed':False}
        output=client.sanitize(output)
        if args.output:
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(output,ensure_ascii=False,indent=2))
    finally: client.close()

if __name__=='__main__': main()
