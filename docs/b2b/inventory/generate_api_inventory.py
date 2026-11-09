#!/usr/bin/env python3
"""Rebuild the baseline API inventory (Python stdlib + local Ruby/Psych)."""
import json, re, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
BASE='af7ab57ead44304b89bbd47dabeeba45b761ce16'
OPEN='api-gateway/src/main/resources/static/openapi.yml'
# These operations share a service and /users/me prefix but have different owners.
# Task delivery opt-ins belong to (tenant,user); Telegram contact binding is global.
NOTIFICATION_POLICIES={
 '/api/v1/users/me/notification-preferences':'TENANT_SELF',
 'notification-settings.proto/notificationsettingsservice/getpreferences':'TENANT_SELF',
 'notification-settings.proto/notificationsettingsservice/updatepreferences':'TENANT_SELF',
 '/api/v1/users/me/telegram/link':'GLOBAL_SELF_SESSION_FOR_MUTATION',
 '/api/v1/users/me/telegram/confirm':'GLOBAL_SELF_SESSION_FOR_MUTATION',
 '/api/v1/users/me/telegram':'GLOBAL_SELF_SESSION_FOR_MUTATION',
 'notification-settings.proto/notificationsettingsservice/createtelegramlink':'GLOBAL_SELF_SESSION_FOR_MUTATION',
 'notification-settings.proto/notificationsettingsservice/confirmtelegramlink':'GLOBAL_SELF_SESSION_FOR_MUTATION',
 'notification-settings.proto/notificationsettingsservice/disconnecttelegram':'GLOBAL_SELF_SESSION_FOR_MUTATION',
}
def source(p,n=1): return {'path':str(p),'line':n,'baseline':BASE}
def policy(path,method='GET'):
 p=path.lower()
 if p in NOTIFICATION_POLICIES:return NOTIFICATION_POLICIES[p],'TAS-325'
 if any(x in p for x in ['admin','readonly']): return 'ADMIN_EXPLICIT_SCOPE','TAS-345,TAS-346,TAS-499'
 if any(x in p for x in ['avatar','profile','users/me','personalaccesstoken','connectedapp']): return 'GLOBAL_SELF_SESSION_FOR_MUTATION','TAS-477,TAS-463,TAS-498'
 if any(x in p for x in ['auth','login','oauth','signup','password','token','inviteuser']): return 'AUTH_PROTOCOL_OR_GLOBAL_IDENTITY','TAS-315,TAS-328,TAS-498'
 if any(x in p for x in ['meta','api-docs','public','well-known']): return 'PUBLIC_SAFE_METADATA','TAS-497'
 if 'telegram' in p and ('webhook' in p or 'process' in p): return 'VERIFIED_INTEGRATION','TAS-325'
 if any(x in p for x in ['notification','preferences']): return 'TENANT_SELF','TAS-325'
 if any(x in p for x in ['directory','users','searchusers']): return 'TENANT_DIRECTORY','TAS-321'
 if 'workflow' in p: return 'TENANT_PROJECT_ACL_CAPABILITY','TAS-330,TAS-342,TAS-493'
 if any(x in p for x in ['attachment','upload','download']): return 'TENANT_PROJECT_ACL_STORAGE','TAS-347,TAS-365,TAS-367,TAS-495'
 if any(x in p for x in ['worklog','comment']): return 'TENANT_RESOURCE_ACL_CAPABILITY','TAS-362'
 if any(x in p for x in ['label','watcher','watchissue','unwatchissue']): return 'TENANT_RESOURCE_ACL_CAPABILITY','TAS-363'
 if any(x in p for x in ['issuelink','/links','linkissues']): return 'TENANT_RESOURCE_ACL_CAPABILITY','TAS-364'
 if ('issue' in p and not any(x in p for x in ['issue-type','issuetype'])) or 'board' in p: return 'TENANT_RESOURCE_ACL_CAPABILITY','TAS-332,TAS-343,TAS-344,TAS-495'
 if 'project' in p: return 'TENANT_PROJECT_ACL_CAPABILITY','TAS-329,TAS-340,TAS-493'
 return 'TENANT_RESOURCE_ACL_CAPABILITY','TAS-332,TAS-343,TAS-344,TAS-495'
java=[]
for p in ROOT.glob('*/src/main/java/**/*.java'):
 s=p.read_text();java.append((str(p.relative_to(ROOT)),s))
def handlers(name,controllers=False):
 hits=[]
 for p,s in java:
  if controllers and '/controller/' not in p:continue
  if not controllers and (p.startswith(('api-gateway/','mcp-server/')) or 'Client' in Path(p).name):continue
  for m in re.finditer(r'\b(?:public|protected)\s+(?:[\w<>.,?\[\]]+\s+)+('+re.escape(name)+r')\s*\(',s):
   hits.append({'name':Path(p).stem+'#'+name,'source':source(p,s.count('\n',0,m.start())+1)})
 return hits
raw=(ROOT/OPEN).read_text()
oas=json.loads(subprocess.check_output(['ruby','-e','require "yaml";require "json";puts JSON.generate(YAML.load_file(ARGV[0]))',str(ROOT/OPEN)]))
records=[]; keys=set()
for p,pi in oas['paths'].items():
 for method,op in pi.items():
  if method not in ['get','put','post','delete','patch','head','options']:continue
  ident=op['operationId'];handler_name={'processTelegramWebhook':'webhook','startOAuth':'start','completeOAuthGet':'callbackGet','completeOAuthPost':'callbackPost','exchangeOAuth':'exchange','signupOAuth':'signup'}.get(ident,ident);m=re.search(r'^\s+operationId:\s*'+re.escape(ident)+r'\s*$',raw,re.M)
  pol,tas=policy(p,method)
  records.append({'kind':'REST_OPENAPI','method':method.upper(),'path':p,'operationId':ident,'handler':handlers(handler_name,True),'source':source(OPEN,raw.count('\n',0,m.start())+1),'scope':pol,'targetPolicy':pol,'implementationTas':tas.split(','),'contractPresence':'STATIC_OPENAPI'})
  keys.add((method.upper(),p))
# Explicit annotated controllers, including routes not in static OpenAPI.
for p,s in java:
 if '@RestController' not in s and '@Controller' not in s:continue
 cls=re.search(r'\bclass\s+\w+',s)
 prefix=''
 if cls:
  cm=re.search(r'@RequestMapping\(\s*"([^"]+)"',s[:cls.start()])
  if cm:prefix=cm.group(1)
 for m in re.finditer(r'@(Get|Post|Put|Patch|Delete|Head|Options)Mapping(?:\((.*?)\))?',s,re.S):
  args=m.group(2) or '';paths=re.findall(r'"(/[^\"]*)"',args) or ['']
  tail=s[m.end():];hm=re.search(r'(?:public\s+(?:[\w<>.,?\[\]]+\s+)+|(?<=\n)  (?:Map<String, Object>\s+))(\w+)\s*\(',tail)
  h=hm.group(1) if hm else ('start' if 'provider/{provider}' in args else 'callback' if 'provider/callback' in args else '<framework>')
  for path in paths:
   path=prefix+path;method=m.group(1).upper();pol,tas=policy(path,method)
   if (method,path) in keys:continue
   records.append({'kind':'HTTP_CONTROLLER','method':method,'path':path,'handler':[{'name':Path(p).stem+'#'+h,'source':source(p,s.count('\n',0,m.start())+1)}],'source':source(p,s.count('\n',0,m.start())+1),'scope':pol,'targetPolicy':pol,'implementationTas':tas.split(','),'contractPresence':'NOT_IN_STATIC_OPENAPI'})
# Protocol endpoints are Spring Security / MCP SDK handlers rather than annotations.
for method,path,handler,src,pol,tas in [
 ('GET','/.well-known/oauth-authorization-server','OAuth2AuthorizationServerMetadataEndpointFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','PUBLIC_SAFE_METADATA','TAS-498'),
 ('GET','/oauth2/authorize','OAuth2AuthorizationEndpointFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','OAUTH_BROWSER_CONSENT','TAS-498,TAS-499'),
 ('POST','/oauth2/authorize','OAuth2AuthorizationEndpointFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','OAUTH_BROWSER_CONSENT','TAS-498,TAS-499'),
 ('POST','/oauth2/token','OAuth2TokenEndpointFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','OAUTH_PROTOCOL_BOUND_GRANT','TAS-498,TAS-499'),
 ('POST','/oauth2/revoke','OAuth2TokenRevocationEndpointFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','OAUTH_PROTOCOL_BOUND_GRANT','TAS-498,TAS-499'),
 ('POST','/oauth2/introspect','OAuth2TokenIntrospectionEndpointFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','SERVICE_AUTHENTICATED','TAS-498'),
 ('GET','/login','LoginPageController.Pages view controller','oauth-server/src/main/java/ru/taska/oauth/LoginPageController.java','PUBLIC_AUTH_UI','TAS-328'),
 ('GET','/consent','LoginPageController.Pages view controller','oauth-server/src/main/java/ru/taska/oauth/LoginPageController.java','OAUTH_BROWSER_CONSENT','TAS-498,TAS-499'),
 ('POST','/login','UsernamePasswordAuthenticationFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','AUTH_PROTOCOL_OR_GLOBAL_IDENTITY','TAS-328'),
 ('POST','/logout','LogoutFilter','oauth-server/src/main/java/ru/taska/oauth/OAuthSecurityConfiguration.java','AUTH_PROTOCOL_OR_GLOBAL_IDENTITY','TAS-315'),
 ('POST','/mcp','MCP Streamable HTTP SDK','mcp-server/src/main/resources/application.yml','TENANT_BOUND_INTEGRATION','TAS-463,TAS-498,TAS-499'),
]:
 records.append({'kind':'HTTP_PROTOCOL','method':method,'path':path,'handler':[{'name':handler,'source':source(src)}],'source':source(src),'scope':pol,'targetPolicy':pol,'implementationTas':tas.split(','),'contractPresence':'FRAMEWORK_NOT_STATIC_OPENAPI'})
for file in sorted(ROOT.glob('grpc-common-lib/src/main/proto/**/*.proto')):
 s=file.read_text();package=re.search(r'package\s+([^;]+);',s).group(1)
 for svc in re.finditer(r'service\s+(\w+)\s*\{(.*?)\}',s,re.S):
  for rpc in re.finditer(r'rpc\s+(\w+)\s*\(\s*([\w.]+)\s*\)\s*returns\s*\(\s*([\w.]+)\s*\)',svc.group(2),re.S):
   name,req,resp=rpc.groups();pol,tas=policy(str(file.name)+'/'+svc.group(1)+'/'+name)
   records.append({'kind':'GRPC','rpc':package+'.'+svc.group(1)+'/'+name,'request':req,'response':resp,'handler':handlers(name[0].lower()+name[1:]),'source':source(str(file.relative_to(ROOT)),s.count('\n',0,svc.start(2)+rpc.start())+1),'scope':pol,'targetPolicy':pol,'implementationTas':tas.split(',')})
for file in [ROOT/'mcp-server/src/main/java/ru/taska/mcp/ReadTools.java',ROOT/'mcp-server/src/main/java/ru/taska/mcp/WriteTools.java']:
 s=file.read_text();matches=list(re.finditer(r'\btool\(\s*"([a-z_]+)"',s))
 for idx,m in enumerate(matches):
  name=m.group(1);end=matches[idx+1].start() if idx+1<len(matches) else s.index('private ToolRegistration tool')
  h=re.search(r'this::(\w+)',s[m.end():end]);handler=h.group(1) if h else 'tools (inline lambda)'
  records.append({'kind':'MCP_TOOL','tool':name,'handler':[{'name':file.stem+'#'+handler,'source':source(str(file.relative_to(ROOT)),s.count('\n',0,m.start())+1)}],'source':source(str(file.relative_to(ROOT)),s.count('\n',0,m.start())+1),'scope':'read' if file.stem=='ReadTools' else 'write','targetPolicy':'TENANT_BOUND_INTEGRATION_AND_DOMAIN_ACL','implementationTas':['TAS-463','TAS-498','TAS-499']})
for r in records:
 if r['kind']=='REST_OPENAPI' and r['operationId'] in ['signup','signupOAuth','startOAuth','completeOAuthGet','completeOAuthPost','exchangeOAuth','processTelegramWebhook']:
  owner='AuthController' if r['operationId']=='signup' else 'TelegramWebhookController' if r['operationId']=='processTelegramWebhook' else 'OAuthController'
  r['handler']=[h for h in r['handler'] if h['name'].startswith(owner+'#')]
 if r.get('path','').startswith('/api/gateway/'):
  r['targetPolicy']='REMOVE_OR_LOCAL_TEST_ONLY';r['implementationTas']=['TAS-316']
 r['currentScope']='NO_TENANT_CONTEXT_IN_BASELINE; existing identity/project ACL applies'
 if r['kind']=='REST_OPENAPI':
  guards=set()
  for h in r['handler']:
   txt=(ROOT/h['source']['path']).read_text();start=sum(len(x) for x in txt.splitlines(True)[:h['source']['line']-1]);tail=txt[start:]
   # Strip string/comment braces before delimiting the first method body.
   clean=re.sub(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*',lambda m:' '*len(m.group(0)),tail,flags=re.S)
   begin=clean.find('{');end=begin;depth=0
   for j in range(begin,len(clean)):
    depth += (clean[j]=='{')-(clean[j]=='}')
    if depth==0:end=j+1;break
   guards.update(re.findall(r'\b(PROTECTED|PUBLIC|GLOBAL_ADMIN_REQUIRED|JWT_SESSION_REQUIRED)\b',clean[begin:end]))
  if not guards and r['operationId'] in ['completeOAuthGet','completeOAuthPost']: guards.add('PUBLIC')
  if not guards and any(h['name'].startswith('WorkflowMutationController#') for h in r['handler']):guards.add('PROTECTED')
  r['currentEndpointSecurity']=sorted(guards)
  r['scope']=','.join(sorted(guards)) or 'CONTROLLER_SPECIFIC_GUARD'
 if r['kind']=='HTTP_PROTOCOL':
  txt=(ROOT/r['source']['path']).read_text()
  anchor='addViewController("'+r['path']+'")' if 'view controller' in r['handler'][0]['name'] else r['path']
  pos=txt.find(anchor)
  if pos<0:pos=txt.find('SecurityFilterChain authorizationServer(')
  if pos>=0:
   r['source']['line']=txt.count('\n',0,pos)+1
   r['handler'][0]['source']['line']=r['source']['line']
counts={k:sum(r['kind']==k for r in records) for k in sorted({r['kind'] for r in records})}
# Check complete emitted records, including method/RPC identity and task ownership,
# independently of the broad path-name classifier. Do not invent target routes.
notification_boundaries=[
 ('REST_OPENAPI','GET /api/v1/users/me/notification-preferences','TENANT_SELF'),
 ('REST_OPENAPI','PATCH /api/v1/users/me/notification-preferences','TENANT_SELF'),
 ('REST_OPENAPI','POST /api/v1/users/me/telegram/link','GLOBAL_SELF_SESSION_FOR_MUTATION'),
 ('REST_OPENAPI','POST /api/v1/users/me/telegram/confirm','GLOBAL_SELF_SESSION_FOR_MUTATION'),
 ('REST_OPENAPI','DELETE /api/v1/users/me/telegram','GLOBAL_SELF_SESSION_FOR_MUTATION'),
 ('REST_OPENAPI','POST /api/v1/integrations/telegram/webhook','VERIFIED_INTEGRATION'),
 ('GRPC','ru.taska.notification.v1.NotificationSettingsService/GetPreferences','TENANT_SELF'),
 ('GRPC','ru.taska.notification.v1.NotificationSettingsService/UpdatePreferences','TENANT_SELF'),
 ('GRPC','ru.taska.notification.v1.NotificationSettingsService/CreateTelegramLink','GLOBAL_SELF_SESSION_FOR_MUTATION'),
 ('GRPC','ru.taska.notification.v1.NotificationSettingsService/ConfirmTelegramLink','GLOBAL_SELF_SESSION_FOR_MUTATION'),
 ('GRPC','ru.taska.notification.v1.NotificationSettingsService/DisconnectTelegram','GLOBAL_SELF_SESSION_FOR_MUTATION'),
 ('GRPC','ru.taska.notification.v1.NotificationSettingsService/ProcessTelegramUpdate','VERIFIED_INTEGRATION'),
]
for kind,identity,expected_policy in notification_boundaries:
 matches=[r for r in records if r['kind']==kind and (r.get('rpc') if kind=='GRPC' else r.get('method','')+' '+r.get('path',''))==identity]
 assert len(matches)==1,'Missing or duplicate notification boundary: '+identity
 assert matches[0]['targetPolicy']==expected_policy and matches[0]['implementationTas']==['TAS-325'],'Incorrect notification boundary: '+identity
 if kind=='GRPC':assert matches[0]['scope']==expected_policy,'Incorrect RPC scope: '+identity
out={'schemaVersion':1,'baseline':BASE,'businessRevision':'Confluence39 D01-D249','counts':counts,'policySemantics':'api-inventory.md#target-policy','operations':records}
import argparse
parser=argparse.ArgumentParser(description='Reconstruct REST/RPC/MCP inventory from baseline source; --check never writes.')
parser.add_argument('--check',action='store_true')
args=parser.parse_args()
inventory_path=ROOT/'docs/b2b/inventory/api-operations.json'
if args.check:
 assert json.loads(inventory_path.read_text())==out,'API inventory drift: review changes before regenerating'
else:
 inventory_path.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(counts));print('Unmatched REST handlers:',[r['operationId'] for r in records if r['kind']=='REST_OPENAPI' and not r['handler']]);print('Unmatched RPC handlers:',[r['rpc'] for r in records if r['kind']=='GRPC' and not r['handler']])
