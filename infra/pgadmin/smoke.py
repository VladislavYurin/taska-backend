"""HTTP smoke: set PGADMIN_CHECK_URL and PGADMIN_DEFAULT_EMAIL/PASSWORD in the environment."""
import http.cookiejar,json,os,re,urllib.request,urllib.parse
base=os.environ['PGADMIN_CHECK_URL']
email=os.environ['PGADMIN_DEFAULT_EMAIL']
password=os.environ['PGADMIN_DEFAULT_PASSWORD']
base=base.rstrip('/')
jar=http.cookiejar.CookieJar();session=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
response=session.open(base+'/',timeout=20);html=response.read().decode()
assert '/login' in response.url,'Anonymous root did not redirect to login'
response=session.open(base+'/browser/',timeout=20)
assert '/login' in response.url,'Anonymous browser was accessible'
html=response.read().decode().replace(chr(92)+chr(34),chr(34))
csrf=re.search(r'csrfToken"\s*:\s*"([^"]+)',html).group(1)
request=urllib.request.Request(base+'/authenticate/login',data=urllib.parse.urlencode(
    {'email':email,'password':password,'csrf_token':csrf}).encode(),
    headers={'Content-Type':'application/x-www-form-urlencoded','X-pgA-CSRFToken':csrf})
response=session.open(request,timeout=20);response.read()
assert '/browser/' in response.url,'Login failed'
session.addheaders.append(('X-pgA-CSRFToken',csrf))
def get(path):
    return json.load(session.open(base+path,timeout=20))
groups=get('/browser/server_group/nodes/')
group=next(str(group['_id']) for group in groups['data'] if group['label']=='Taska')
server_response=get('/browser/server/nodes/'+group+'/');servers=server_response['result']
assert len(servers)==6,len(servers)
for server in servers:
    request=urllib.request.Request(base+'/browser/server/connect/'+group+'/'+str(server['_id']),data=b'{}',
        headers={'Content-Type':'application/json','X-pgA-CSRFToken':csrf})
    result=json.load(session.open(request,timeout=20))
    assert result.get('success')==1,result
    print('connected_without_database_password',server['label'])
print('pgadmin_smoke_passed',base,'login_required',True,'servers',len(servers))
