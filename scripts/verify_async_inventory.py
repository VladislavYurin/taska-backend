#!/usr/bin/env python3
"""Verify the reviewed B2B non-HTTP inventory against actual Java sources.
No network, build, credentials or source changes. Lexical anchors, not a Java parser;
the independently reviewed path catalog explains semantics. Any anchor or source
hash drift requires review, even if the number of matches remains unchanged.
"""
import argparse, collections, hashlib, json, re, sys
from pathlib import Path
RULES = {
    "startup": r"@(?:jakarta\.annotation\.)?PostConstruct|implements\s+ApplicationRunner",
    "framework_store": r"new\s+Jdbc(?:OAuth2AuthorizationConsentService|OAuth2AuthorizationService|RegisteredClientRepository)\s*\(",
    "scheduled": r"@Scheduled\s*\(",
    "consumer": r"@KafkaListener\s*\(",
    "publisher": r"extends\s+AbstractOutboxEventPublisher|kafkaSender\s*\.\s*send\s*\(|new\s+DeadLetterPublishingRecoverer",
    "event_write": r"\.saveOutboxEvent\s*\(|\.saveProjectCreated\s*\(|\.saveProjectUpdated\s*\(|\.saveProjectArchived\s*\(|\.saveMember(?:Added|Updated|Removed)\s*\(|(?:outbox|outboxEventRepository)\.save\s*\(|\.buildUser(?:Activated|Invited)OutboxEvent\s*\(",
    "entity_query": r"(?:r2dbcEntityTemplate|entityTemplate|r2dbcTemplate)\.(?:select|count|exists|insert|update|delete)\s*\(",
    "native_sql": r"\.sql\s*\(|@Query\s*\(|jdbc\.(?:query|queryForObject|queryForList|update|execute)\s*\(|\.createStatement\s*\(",
    "sql_builder": r"new\s+SqlQuery\s*\(|new\s+SqlWhere\s*\(|\.createSelect\s*\(",
    "cache": r"new\s+(?:java\.util\.concurrent\.)?ConcurrentHashMap\s*<|\.cache\s*\(|\.share\s*\(|new\s+HttpSessionRequestCache|taska\.metadata_until|\.setAttribute\s*\(",
    "storage": r"(?:storageClient|storage|s3AsyncClient|presigner|s3Presigner)(?:\.|::)(?:createPresigned\w+|presign\w+|putObject|getObject|deleteObject|headObject|objectExists|validateAndGetUploadedObjectMetadata)",
    "repository": r"extends\s+(?:ReactiveCrudRepository|R2dbcRepository|JdbcRegisteredClientRepository)",
}
def scan(root):
    result=[]
    for path in sorted(root.glob('*/src/main/java/**/*.java')):
        raw=path.read_text(); relative=path.relative_to(root).as_posix()
        # Mask comments only. SQL strings are deliberately included as anchor inputs.
        clean=re.sub(r'/\*.*?\*/|//[^\n]*',lambda m:''.join('\n' if c=='\n' else ' ' for c in m[0]),raw,flags=re.S)
        for category,pattern in RULES.items():
            for match in re.finditer(pattern,clean):
                line=raw.count('\n',0,match.start())+1
                result.append({'id':f'{relative}:{line}:{category}', 'source':relative,'line':line,'kind':category})
    # A line can call the same primitive twice: one anchor carries the full line hash.
    return list({r['id']:r for r in sorted(result,key=lambda v:v['id'])}.values())

def method_label(raw, line, kind, class_name):
    # Ignore SQL/JSON/string literals while locating Java declarations. Preserve offsets.
    lexical=re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
    clean=lexical.sub(lambda m:''.join('\n' if c=='\n' else ' ' for c in m[0]),raw)
    declarations=[]
    pattern=re.compile(r'(?m)^\s*(?:(?:public|protected|private|static|final|synchronized|default|abstract)\s+)*(?:[\w.$]+(?:\s*<[^;{}=]+>)?(?:\[\])?\s+)+(?P<name>[A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[\w., ]+)?(?P<term>[{;])')
    for m in pattern.finditer(clean):
        if m['name'] not in ['if','for','while','switch','catch','return','new'] and m[0].strip().split()[0] not in ['return','throw','new','var','else']:
            declarations.append((raw.count('\n',0,m.start())+1,raw.count('\n',0,m.end())+1,m['name']))
    evidence=raw.splitlines()[line-1]
    if kind=='repository':return '<repository-interface>'
    if kind=='publisher' and 'extends Abstract' in evidence:return '<publisher-class>'
    if 'ConcurrentHashMap' in evidence:return '<field-initializer>'
    if class_name=='ToolResolver' and 'this.projects =' in evidence:return 'Session.<init>'
    if kind in ['scheduled','consumer','startup'] or '@Query' in evidence:
        found=[d for d in declarations if d[1]>=line]
        return found[0][2] if found else '<class-initializer>'
    found=[d for d in declarations if d[0]<=line]
    return found[-1][2] if found else '<class-initializer>'

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);args=ap.parse_args()
    root=args.root.resolve(); data=json.loads((root/'docs/b2b/inventory/async-paths.json').read_text())
    actual={a['id']:a for a in scan(root)}; stored={a['id']:a for a in data['anchors']}; failures=[]
    for key in sorted(actual.keys()-stored.keys()):failures.append('UNREVIEWED '+key)
    for key in sorted(stored.keys()-actual.keys()):failures.append('STALE '+key)
    profiles=data['paths']; required=('owner','scope','trust','retry','dedup','fence','targetTasks','current','target')
    for a in stored.values():
        if a['pathId'] not in profiles:failures.append('MISSING PROFILE '+a['id'])
        if not a.get('method'):failures.append('MISSING METHOD '+a['id'])
        source=root/a['source']
        if source.exists() and a['method'] != method_label(source.read_text(),a['line'],a['kind'],Path(a['source']).stem):failures.append('METHOD DRIFT '+a['id'])
    for key,p in profiles.items():
        for field in required:
            if not p.get(field):failures.append('MISSING '+field+' '+key)
        if not (root/p['source']).is_file():failures.append('MISSING SOURCE '+key)
    for source,expected in data['sourceHashes'].items():
        path=root/source
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:failures.append('SOURCE CHANGED '+source)
    matrix=json.loads((root/'docs/b2b/inventory/access-matrix.json').read_text())
    names=[a['action'] for a in matrix['actions']]
    if len(names)!=len(set(names)):failures.append('DUPLICATE ACTION')
    for action in matrix['actions']:
        if action['lifecyclePolicy'] not in matrix['lifecyclePolicies']:failures.append('MISSING LIFECYCLE '+action['action'])
        if set(action['roles']) != {'OWNER','TENANT_ADMIN','PROJECT_ADMIN','MEMBER','VIEWER','GLOBAL_ADMIN'}:failures.append('INCOMPLETE ROLE MATRIX '+action['action'])
    for planned in data['plannedPaths']:
        for field in ('owner','scope','durableKey','trust','retry','dedup','fence','targetTasks'):
            if not planned.get(field):failures.append('MISSING PLANNED '+field+' '+planned['id'])
    if data['scanRules'] != RULES:failures.append('SCAN RULES CHANGED: review and regenerate anchors')
    if failures:
        print('\n'.join(failures));return 1
    print(json.dumps({'status':'PASS','baseline':data['baseline'],'anchors':len(actual),'reviewedPaths':len(profiles),'sources':len(data['sourceHashes']),'actions':len(matrix['actions']),'plannedPaths':len(data['plannedPaths']),'kinds':dict(sorted(collections.Counter(a['kind'] for a in actual.values()).items()))},ensure_ascii=False))
    return 0
if __name__=='__main__':sys.exit(main())
