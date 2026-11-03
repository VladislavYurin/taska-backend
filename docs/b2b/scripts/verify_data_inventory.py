#!/usr/bin/env python3
"""Reconstruct actual PostgreSQL schema from versioned migrations; never use a shared DB.
Default checks source hashes, CREATE TABLE coverage and @Table/@Column mappings.
--postgres additionally runs all migrations on an isolated postgres:16 container and
compares every column/constraint/index. --target-postgres checks all designed DDL, RLS and financial CHECK constraints.
--write regenerates the inventory (requires
--postgres). Docker container has no network, host ports or persistent volume.
"""
import argparse, hashlib, json, pathlib, re, subprocess, sys, time, uuid
ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = ROOT / 'docs/b2b/inventory/tables.json'
SQL_QUERY = r"""
SELECT COALESCE(json_agg(t ORDER BY owner_schema, name), '[]') FROM (
 SELECT n.nspname AS owner_schema, c.relname AS name,
 (SELECT json_agg(json_build_object('name',a.attname,'type',format_type(a.atttypid,a.atttypmod),'nullable',NOT a.attnotnull,'default',pg_get_expr(d.adbin,d.adrelid)) ORDER BY a.attnum)
 FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped) AS columns,
 COALESCE((SELECT json_agg(json_build_object('name',co.conname,'kind',co.contype,'definition',pg_get_constraintdef(co.oid,true),'columns',(SELECT json_agg(a.attname ORDER BY x.ord) FROM unnest(co.conkey) WITH ORDINALITY x(num,ord) JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=x.num),'referenced_table',CASE WHEN co.confrelid<>0 THEN co.confrelid::regclass::text ELSE NULL END,'referenced_columns',(SELECT json_agg(a.attname ORDER BY x.ord) FROM unnest(co.confkey) WITH ORDINALITY x(num,ord) JOIN pg_attribute a ON a.attrelid=co.confrelid AND a.attnum=x.num)) ORDER BY co.conname) FROM pg_constraint co WHERE co.conrelid=c.oid),'[]') AS constraints,
 COALESCE((SELECT json_agg(json_build_object('name',ic.relname,'unique',i.indisunique,'primary',i.indisprimary,'definition',pg_get_indexdef(i.indexrelid),'predicate',pg_get_expr(i.indpred,i.indrelid)) ORDER BY ic.relname) FROM pg_index i JOIN pg_class ic ON ic.oid=i.indexrelid WHERE i.indrelid=c.oid),'[]') AS indexes,
 c.relrowsecurity AS rls_enabled, c.relforcerowsecurity AS rls_forced
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r' AND n.nspname IN ('taska','public')
) t;
"""
def run(args, data=None):
    p = subprocess.run(args, input=data, text=True, capture_output=True)
    if p.returncode: raise RuntimeError('Command failed: '+ ' '.join(args[:5])+'\n'+p.stderr[-2500:])
    return p.stdout.strip()
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def discover():
    groups = {}
    for master in sorted(ROOT.glob('*/src/main/resources/db/changelog/db.changelog-master.yaml')):
        txt=master.read_text(); owner=master.parts[len(ROOT.parts)]
        if 'includeAll:' in txt:
            directory=re.search(r'path:\s*([^\s]+)',txt).group(1)
            paths=sorted((master.parent/directory).glob('*.sql'))
        else:
            paths=[master.parents[2]/m for m in re.findall(r'file:\s*([^\s]+)',txt)]
        if set(paths)!=set(master.parent.rglob('*.sql')): raise AssertionError(f'Orphan/unhandled SQL files: {owner}')
        groups[owner]=paths
    return groups
CREATE=re.compile(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:(\w+)\.)?(\w+)\s*\(',re.I)
def creations(groups):
    return {(owner,schema or 'public',table) for owner,ps in groups.items() for p in ps for schema,table in CREATE.findall(re.sub(r'--[^\n]*','',p.read_text()))}
def models():
    result=[]
    for p in sorted(ROOT.glob('*/src/main/java/**/*.java')):
        txt=p.read_text(); m=re.search(r'@Table\s*\(([^)]+)\)',txt)
        if not m: continue
        v=re.search(r'(?:name|value)\s*=\s*"([^"]+)"',m.group(1)) or re.match(r'\s*"([^"]+)"',m.group(1))
        schema=re.search(r'schema\s*=\s*"([^"]+)"',m.group(1))
        if not v: raise AssertionError(f'Unhandled Table annotation {p}')
        cols=re.findall(r'@Column\s*\(\s*(?:value\s*=\s*)?"([^"]+)"',txt)
        # Only explicitly mapped columns are claimed; SQL/JDBC-only tables remain included.
        result.append({'owner':p.parts[len(ROOT.parts)],'schema':schema.group(1) if schema else 'public','table':v.group(1),'path':str(p.relative_to(ROOT)),'sha256':digest(p),'explicit_columns':sorted(set(cols))})
    return result
GLOBAL_AUTH={'users','credentials','invite_tokens','user_avatars','signup_rate_limits','oauth_states','oauth_grants'}
GLOBAL_NOTIFY={'notification_preferences','telegram_link_requests','telegram_updates','notification_channel_throttle'}
def scope(owner,table):
    if owner=='admin-service': return 'PLATFORM_AUDIT'
    if owner=='auth-service': return 'GLOBAL_IDENTITY' if table in GLOBAL_AUTH else 'MIXED_TARGET'
    if owner=='oauth-server': return 'SYSTEM' if table=='oauth_registration_lock' else ('GLOBAL_INTEGRATION' if table=='oauth2_registered_client' else 'GRANT_SCOPED')
    if owner=='notification-service': return 'GLOBAL_ACCOUNT' if table in GLOBAL_NOTIFY else 'MIXED_TARGET'
    return 'TENANT_TARGET'
def decorate(owner,t,paths):
    t['owner']=owner; t['scope']=scope(owner,t['name']); t['inventory_class']='APPLICATION'
    refs=[]
    for p in paths:
        if re.search(r'\b'+re.escape(t['name'])+r'\b',re.sub(r'--[^\n]*','',p.read_text()),re.I): refs.append(str(p.relative_to(ROOT)))
    t['source_files']=refs
    return t

def reconstruct(groups):
    name='taska-inventory-'+uuid.uuid4().hex[:12]
    run(['docker','run','-d','--rm','--name',name,'--network','none','--tmpfs','/var/lib/postgresql/data:rw','-e','POSTGRES_HOST_AUTH_METHOD=trust','postgres:16-alpine'])
    try:
        for _ in range(60):
            p=subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True)
            if not p.returncode: break
            time.sleep(.2)
        else: raise RuntimeError('Isolated PostgreSQL did not start')
        tables=[]
        for i,(owner,paths) in enumerate(groups.items()):
            db='inventory_'+str(i)
            run(['docker','exec',name,'createdb','-U','postgres',db])
            for path in paths:
                run(['docker','exec','-i',name,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',db],path.read_text())
            rows=json.loads(run(['docker','exec','-i',name,'psql','-X','-A','-t','-U','postgres','-d',db],SQL_QUERY))
            tables.extend(decorate(owner,t,paths) for t in rows)
        return tables
    finally:
        run(['docker','rm','-f',name])

def system_tables(owners):
    # Liquibase engine metadata is not CREATE TABLE in application SQL. Never classify it as tenant business data.
    def col(n,t,nullable=True): return {'name':n,'type':t,'nullable':nullable,'default':None}
    changelog=[col('id','character varying(255)',False),col('author','character varying(255)',False),col('filename','character varying(255)',False),col('dateexecuted','timestamp without time zone',False),col('orderexecuted','integer',False),col('exectype','character varying(10)',False)]+[col(n,t) for n,t in [('md5sum','character varying(35)'),('description','character varying(255)'),('comments','character varying(255)'),('tag','character varying(255)'),('liquibase','character varying(20)'),('contexts','character varying(255)'),('labels','character varying(255)'),('deployment_id','character varying(10)')]]
    lock=[col('id','integer',False),col('locked','boolean',False),col('lockgranted','timestamp without time zone'),col('lockedby','character varying(255)')]
    return [{'owner':o,'name':n,'inventory_class':'SYSTEM','scope':'SYSTEM','schema_resolution':'Liquibase configuration; auth supports public or taska history; runtime unverified','columns':cols,'constraints':([] if n=='databasechangelog' else [{'kind':'p','columns':['id']}]),'indexes':([] if n=='databasechangelog' else [{'primary':True,'unique':True,'columns':['id']}]),'source':'Liquibase engine metadata contract; not emitted by application migrations','reconstructed_from_sql':False} for o in owners for n,cols in [('databasechangelog',changelog),('databasechangeloglock',lock)]]

def validate_tables(tables,groups,mods):
    actual={(t['owner'],t['owner_schema'],t['name']) for t in tables}
    expected=creations(groups)
    if actual!=expected: raise AssertionError(f'CREATE TABLE mismatch missing={expected-actual} extra={actual-expected}')
    bykey={(t['owner'],t['owner_schema'],t['name']):t for t in tables}
    for model in mods:
        key=(model['owner'],model['schema'],model['table'])
        if key not in bykey: raise AssertionError(f'Model table not in SQL: {model["path"]}')
        missing=set(model['explicit_columns'])-{c['name'] for c in bykey[key]['columns']}
        if missing: raise AssertionError(f'Model mapped columns missing in SQL: {model["path"]}: {missing}')

def target_ddl(model):
    """Render designed tables to executable scratch DDL; catches impossible FK/index/check definitions."""
    groups={}
    for table in model['existing_tables']+model['new_tables']:
        owner=table['owner']; groups.setdefault(owner,[]).append(table)
    result={}
    for owner,tables in groups.items():
        statements=['CREATE SCHEMA IF NOT EXISTS taska;','CREATE EXTENSION IF NOT EXISTS pgcrypto;','CREATE EXTENSION IF NOT EXISTS pg_trgm;']
        later=[]
        for t in tables:
            schema=t.get('owner_schema',t.get('schema','taska')); q=schema+'.'+t['name']
            defs=[]
            for c in t['columns']:
                value=c['name']+' '+c['type']
                if not c['nullable']: value+=' NOT NULL'
                if c.get('default') is not None:value+=' DEFAULT '+c['default']
                defs.append(value)
            if 'constraints' in t:
                for con in t['constraints']:
                    sql='CONSTRAINT '+con['name']+' '+con['definition']
                    if con['kind']=='f':later.append('ALTER TABLE '+q+' ADD '+sql+';')
                    else:defs.append(sql)
            else:
                defs.append('PRIMARY KEY ('+', '.join(t['primary_key'])+')')
                defs.extend('UNIQUE ('+', '.join(u)+')' for u in t['unique_constraints'])
                defs.extend('CHECK ('+check+')' for check in t['checks'])
                for f in t['foreign_keys']:
                    sql='ALTER TABLE '+q+' ADD FOREIGN KEY ('+', '.join(f['columns'])+') REFERENCES '+schema+'.'+f['table']+' ('+', '.join(f['referenced_columns'])+') ON DELETE '+f['on_delete']
                    if f.get('deferrable'):sql+=' DEFERRABLE INITIALLY DEFERRED'
                    later.append(sql+';')
            statements.append('CREATE TABLE '+q+' (\n'+',\n'.join(defs)+'\n);')
            if 'constraints' in t:
                automatic={con['name'] for con in t['constraints'] if con['kind'] in 'pu'}
                for ix in t['indexes']:
                    if ix['name'] not in automatic and not ix.get('primary'):later.append(ix['definition']+';')
            else:
                for i,ix in enumerate(t['indexes']):
                    unique=ix.startswith('UNIQUE ')
                    later.append('CREATE '+('UNIQUE ' if unique else '')+'INDEX '+t['name']+'_target_ix_'+str(i)+' ON '+q+' '+(ix[7:] if unique else ix)+';')
            for guard in t.get('mutation_guards',[]):
                if guard['trigger']!='BEFORE UPDATE' or guard['error_sqlstate']!='23514':raise AssertionError('Unsupported target mutation guard')
                columns={c['name'] for c in t['columns']}
                guarded=set(guard['always_immutable_columns']+guard['frozen_columns']+[guard['freeze_when_old_not_null']])
                if not guarded<=columns:raise AssertionError('Unknown mutation guard column')
                function=schema+'.'+guard['name']+'_fn'
                checks=[]
                for column in guard['always_immutable_columns']:
                    checks.append('OLD.'+column+' IS DISTINCT FROM NEW.'+column)
                old_row='ROW('+','.join('OLD.'+c for c in guard['frozen_columns'])+')'
                new_row='ROW('+','.join('NEW.'+c for c in guard['frozen_columns'])+')'
                checks.append('(OLD.'+guard['freeze_when_old_not_null']+' IS NOT NULL AND '+old_row+' IS DISTINCT FROM '+new_row+')')
                later.append('CREATE FUNCTION '+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+' OR '.join(checks)+" THEN RAISE check_violation USING MESSAGE='immutable policy evidence bundle'; END IF; RETURN NEW; END $$;")
                later.append('CREATE TRIGGER '+guard['name']+' BEFORE UPDATE ON '+q+' FOR EACH ROW EXECUTE FUNCTION '+function+'();')
        roles=[]
        short=owner.replace('-service','').replace('-','_')
        role_names=['taska_'+short+'_'+x for x in ('app','admin','job')]
        for role in role_names:roles.append('CREATE ROLE '+role+' NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS; GRANT USAGE ON SCHEMA taska,public TO '+role+';')
        for t in tables:
            if t['scope']!='TENANT':continue
            q=t.get('owner_schema',t.get('schema','taska'))+'.'+t['name'];key='id' if t['name']=='tenants' else 'tenant_id'
            condition=key+"=NULLIF(current_setting('app.tenant_id',true),'')::uuid"
            roles.extend(['ALTER TABLE '+q+' ENABLE ROW LEVEL SECURITY;','ALTER TABLE '+q+' FORCE ROW LEVEL SECURITY;','CREATE POLICY tenant_guard ON '+q+' TO '+','.join(role_names)+' USING ('+condition+') WITH CHECK ('+condition+');','GRANT SELECT,INSERT,UPDATE,DELETE ON '+q+' TO '+','.join(role_names)+';'])
        result[owner]='\n'.join(statements+later+roles)
    return result

def verify_target(model, postgres=False):
    tables=model['existing_tables']+model['new_tables']; by={(t['owner'],t['name']):t for t in tables}
    if len(by)!=len(tables):raise AssertionError('Duplicate target table')
    for t in tables:
        columns=[c['name'] for c in t['columns']]
        if len(columns)!=len(set(columns)):raise AssertionError('Duplicate target column: '+t['name'])
        fks=t.get('foreign_keys',[]) or [dict(columns=x['columns'],table=x['referenced_table'].split('.')[-1],referenced_columns=x['referenced_columns']) for x in t.get('constraints',[]) if x['kind']=='f']
        for f in fks:
            if not set(f['columns'])<=set(columns):raise AssertionError('Missing local FK column: '+t['name'])
            ref=by[(t['owner'],f['table'])]
            if not set(f['referenced_columns'])<={c['name'] for c in ref['columns']}:raise AssertionError('Missing remote FK column: '+t['name'])
    if postgres:
        name='taska-target-schema-'+uuid.uuid4().hex[:12]
        run(['docker','run','-d','--rm','--name',name,'--network','none','--tmpfs','/var/lib/postgresql/data:rw','-e','POSTGRES_HOST_AUTH_METHOD=trust','postgres:16-alpine'])
        try:
            for _ in range(60):
                p=subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True)
                if not p.returncode:break
                time.sleep(.2)
            else:raise RuntimeError('Target PostgreSQL did not start')
            for i,(owner,ddl) in enumerate(target_ddl(model).items()):
                db='target_'+str(i);run(['docker','exec',name,'createdb','-U','postgres',db])
                try:run(['docker','exec','-i',name,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',db],ddl)
                except RuntimeError as e:raise RuntimeError(owner+': '+str(e)) from e
                if owner=='billing-service':
                    psql=['docker','exec','-i',name,'psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','postgres','-d',db]
                    tenant='00000000-0000-0000-0000-000000000001'
                    when='2026-10-01T12:00:00Z'
                    fact=dict(id=str(uuid.uuid4()),tenant_id=tenant,provider='TEST',provider_account_id='test-account',external_fact_id='test-entry',fact_kind='PAYMENT',parent_fact_id=None,amount_minor=20,currency='EUR',currency_exponent=2,occurred_at=when,observed_at=when,verified_at=when,verified_by=None,evidence_ref='private-test-reference',evidence_digest='proof',schema_version=1,created_at=when,occurred_precision='EXACT',occurred_not_before=None,occurred_before=None,payer_ref='verified-payer-ref',source_method='WIRE')
                    def insert_sql(table,values):
                        def literal(value):return 'NULL' if value is None else "'"+str(value).replace("'","''")+"'"
                        return 'INSERT INTO taska.'+table+' ('+','.join(values)+') VALUES ('+','.join(literal(v) for v in values.values())+');'
                    def check_insert(table,values,should_pass):
                        result=subprocess.run(psql,input='BEGIN;'+insert_sql(table,values)+'ROLLBACK;',text=True,capture_output=True)
                        if (result.returncode==0)!=should_pass:raise AssertionError('Target CHECK probe failed: '+table+' '+str(should_pass)+' '+result.stderr[-1000:])
                        if not should_pass and 'check constraint' not in result.stderr:raise AssertionError('Probe rejected for wrong reason: '+result.stderr[-1000:])
                    check_insert('payment_facts',fact,True)
                    check_insert('payment_facts',{**fact,'occurred_at':None},False)
                    day={**fact,'occurred_precision':'DAY','occurred_at':None,'occurred_not_before':'2026-10-01T00:00:00Z','occurred_before':'2026-10-02T00:00:00Z'}
                    check_insert('payment_facts',day,True)
                    check_insert('payment_facts',{**day,'occurred_at':when},False)
                    check_insert('payment_facts',{**day,'occurred_before':'2026-09-30T00:00:00Z'},False)
                    check_insert('payment_facts',{**fact,'payer_ref':None},False)
                    run(psql,insert_sql('payment_facts',fact))
                    refund=dict(id=str(uuid.uuid4()),tenant_id=tenant,source_payment_fact_id=fact['id'],invoice_id=None,kind='UNALLOCATED_RETURN',requested_by=str(uuid.uuid4()),amount_minor=5,state='REQUESTED',operation_id=str(uuid.uuid4()),provider_request_id=None,confirmed_fact_id=None,reason_code='EXCESS',created_at=when,updated_at=when,source_method='WIRE',fee_bearer='TASKA')
                    check_insert('refunds',refund,True)
                    check_insert('refunds',{**refund,'kind':'SUBSCRIPTION_FULL'},False)
                    sla=dict(businessDays=5,feeBearer='TASKA',countingRule='T_PLUS_5_BUSINESS_DATES_SAME_LOCAL_TIME',timezone='Europe/Madrid',calendarDigest='a'*64,excludeEvidenceDate=True,dstGapRule='NEXT_VALID_INSTANT',dstOverlapRule='LATER_OFFSET')
                    complete={**refund,'evidence_completed_at':when,'business_calendar_id':'SANDBOX_ONLY_ES_FIXTURE','calendar_version':'fixture-v1','sla_policy_version':'WIRE_RETURN_ES_V1','sla_snapshot':json.dumps(sla),'decision_due_at':'2026-10-08T12:00:00Z','send_due_at':'2026-10-08T12:00:00Z'}
                    check_insert('refunds',complete,True)
                    check_insert('refunds',{**complete,'fee_bearer':'PRINCIPAL_DEDUCT'},False)
                    check_insert('refunds',{**complete,'sla_snapshot':json.dumps({**sla,'feeBearer':None})},False)
                    check_insert('refunds',{**complete,'calendar_version':None},False)
                    check_insert('refunds',{**complete,'send_due_at':'2026-10-09T12:00:00Z'},False)
                    check_insert('refunds',{**complete,'decided_at':'2026-10-12T12:00:00Z'},True)
                    run(psql,insert_sql('refunds',complete))
                    def check_update(assignment,should_pass):
                        sql="BEGIN; UPDATE taska.refunds SET "+assignment+" WHERE id='"+complete['id']+"'; ROLLBACK;"
                        response=subprocess.run(psql,input=sql,text=True,capture_output=True)
                        if (response.returncode==0)!=should_pass:raise AssertionError('SLA mutation guard probe failed: '+response.stderr[-1000:])
                        if not should_pass and 'immutable policy evidence bundle' not in response.stderr:raise AssertionError('Mutation rejected for wrong reason: '+response.stderr[-1000:])
                    check_update("evidence_completed_at='2026-10-02T12:00:00Z'",False)
                    check_update("calendar_version='fixture-v2'",False)
                    check_update("sla_snapshot=jsonb_set(sla_snapshot,'{calendarDigest}',to_jsonb(repeat('b',64)))",False)
                    check_update("decision_due_at='2026-10-09T12:00:00Z',send_due_at='2026-10-09T12:00:00Z'",False)
                    check_update("decided_at='2026-10-12T12:00:00Z',updated_at='2026-10-12T12:00:00Z'",True)
                    loss={**fact,'id':str(uuid.uuid4()),'external_fact_id':'partial-chargeback','parent_fact_id':fact['id'],'fact_kind':'CHARGEBACK','amount_minor':5}
                    run(psql,insert_sql('payment_facts',loss))
                    dispute=dict(id=str(uuid.uuid4()),tenant_id=tenant,payment_fact_id=fact['id'],provider='TEST',external_dispute_id='partial-loss-case',state='LOST',amount_minor=5,confirmed_money_fact_id=loss['id'],version=1,opened_at=when,closed_at='2026-10-02T12:00:00Z',final_outcome='LOST_PARTIAL_COVERAGE_REVOKED',resolution_evidence_ref='private-resolution-proof',resolution_evidence_digest='proof',resolution_verified_at='2026-10-02T12:00:00Z')
                    check_insert('disputes',dispute,True)
                    check_insert('disputes',{**dispute,'final_outcome':None},False)
                    check_insert('disputes',{**dispute,'confirmed_money_fact_id':None},False)
                    check_insert('disputes',{**dispute,'state':'WON'},False)
                    check_insert('disputes',{**dispute,'state':'NEEDS_REVIEW'},False)
                    check_insert('disputes',{**dispute,'state':'WON','final_outcome':'WON','confirmed_money_fact_id':None},True)
                if owner=='tenant-service':
                    psql=['docker','exec','-i',name,'psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','postgres','-d',db]
                    tenant=str(uuid.uuid4()); user=str(uuid.uuid4())
                    seed="INSERT INTO taska.free_eligibility_ledger(email_digest,digest_key_version,first_user_id,bound_tenant_id,consumed_at,operation_id,purpose_version,review_due_at) VALUES "
                    values=["('"+digest+"',1,'"+user+"','"+tenant+"','2026-11-01',gen_random_uuid(),'ES_V1','2027-11-01')" for digest in ('owner-before','owner-after')]
                    run(psql,seed+','.join(values)+';')
                    run(psql,"UPDATE taska.free_eligibility_ledger SET first_user_id=NULL WHERE email_digest='owner-before';")
                    bad=subprocess.run(psql,input="BEGIN; UPDATE taska.free_eligibility_ledger SET bound_tenant_id=NULL WHERE email_digest='owner-after'; ROLLBACK;",text=True,capture_output=True)
                    if bad.returncode==0 or 'check constraint' not in bad.stderr:raise AssertionError('FREE partial binding cleanup admitted')
                    run(psql,"UPDATE taska.free_eligibility_ledger SET bound_tenant_id=NULL,first_user_id=NULL,operation_id=NULL WHERE bound_tenant_id='"+tenant+"';")
                    if run(psql,"SELECT count(*) FROM taska.free_eligibility_ledger WHERE bound_tenant_id IS NULL AND first_user_id IS NULL AND operation_id IS NULL;").splitlines()[-1]!='2':raise AssertionError('FREE cleanup did not clear all owner markers')
                if owner=='admin-service':
                    psql=['docker','exec','-i',name,'psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','postgres','-d',db]
                    case=str(uuid.uuid4());permit=str(uuid.uuid4())
                    details=json.dumps(dict(caseId=case,permitId=permit,ticketHash='a'*64,ticketExpiresAt='2026-11-03T12:10:00Z',documentIds=['fixture-doc'],archiveVersion='fixture-v1',verifiedRecipientRef='fixture-recipient'))
                    def consume_sql(event_key):
                        return "INSERT INTO taska.admin_audit_log(id,actor_user_id,actor_display_name,actor_roles,action,target_service,target_table,target_id,reason,channel,actor_auth_version,operation_id,case_id,permit_id,case_event_key,details_json) VALUES (gen_random_uuid(),gen_random_uuid(),'Fixture','[]','DELIVERY_PERMIT_CONSUMED','billing-service','financial_documents','fixture-doc','Verified fixture request','WEB',1,gen_random_uuid(),'"+case+"','"+permit+"','"+event_key+"','"+details+"');"
                    run(psql,consume_sql('consume-1'))
                    duplicate=subprocess.run(psql,input=consume_sql('consume-2'),text=True,capture_output=True)
                    if duplicate.returncode==0 or 'admin_audit_permit_consumed_unique' not in duplicate.stderr:raise AssertionError('Archive duplicate permit consumption admitted: '+duplicate.stderr[-1000:])
                    mutate=subprocess.run(psql,input="UPDATE taska.admin_audit_log SET reason='Changed' WHERE permit_id='"+permit+"';",text=True,capture_output=True)
                    if mutate.returncode==0 or 'immutable policy evidence bundle' not in mutate.stderr:raise AssertionError('Archive consume receipt mutation admitted')
                if owner=='project-service':
                    psql=['docker','exec','-i',name,'psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','postgres','-d',db]
                    tenant_a='00000000-0000-0000-0000-000000000001';tenant_b='00000000-0000-0000-0000-000000000002'
                    run(psql,"INSERT INTO taska.projects(tenant_id,project_key,name,created_by) VALUES ('"+tenant_a+"','ONE','One',gen_random_uuid()),('"+tenant_b+"','TWO','Two',gen_random_uuid());")
                    for role in ('taska_project_app','taska_project_admin','taska_project_job'):
                        absent=run(psql,'SET ROLE '+role+'; SELECT count(*) FROM taska.projects;')
                        if absent.splitlines()[-1]!='0':raise AssertionError('RLS missing-tenant read leaked for '+role)
                        scoped=run(psql,'SET ROLE '+role+"; BEGIN; SET LOCAL app.tenant_id='"+tenant_a+"'; SELECT count(*) FROM taska.projects; ROLLBACK;")
                        if '\n1\n' not in '\n'+scoped+'\n':raise AssertionError('RLS tenant read failed for '+role)
                        blocked=subprocess.run(psql,input='SET ROLE '+role+"; BEGIN; SET LOCAL app.tenant_id='"+tenant_a+"'; INSERT INTO taska.projects(tenant_id,project_key,name,created_by) VALUES ('"+tenant_b+"','BAD','Wrong',gen_random_uuid()); COMMIT;",text=True,capture_output=True)
                        if blocked.returncode==0 or 'row-level security' not in blocked.stderr:raise AssertionError('RLS cross-tenant write did not reject for '+role)
                    admin=run(psql,"SET ROLE taska_project_admin; BEGIN; SET LOCAL app.tenant_id='"+tenant_b+"'; SELECT count(*) FROM taska.projects; COMMIT; SELECT count(*) FROM taska.projects;")
                    if admin.splitlines()[-1]!='0':raise AssertionError('SET LOCAL tenant leaked after commit')
        finally:run(['docker','rm','-f',name])
    return dict(existing_target_tables=len(model['existing_tables']),new_target_tables=len(model['new_tables']),target_ddl='PASS' if postgres else 'NOT_RUN',target_rls_probes=10 if postgres else 0,target_check_probes=20 if postgres else 0,target_mutation_guard_probes=5 if postgres else 0,target_free_binding_probes=4 if postgres else 0,target_archive_consume_probes=3 if postgres else 0)

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--postgres',action='store_true');ap.add_argument('--write',action='store_true');ap.add_argument('--target-postgres',action='store_true'); args=ap.parse_args()
    if args.write and not args.postgres: ap.error('--write requires --postgres')
    groups=discover(); mods=models()
    source_files=[{'path':str(p.relative_to(ROOT)),'sha256':digest(p)} for ps in groups.values() for p in ps]
    masters=[{'path':str(p.relative_to(ROOT)),'sha256':digest(p)} for p in sorted(ROOT.glob('*/src/main/resources/db/changelog/db.changelog-master.yaml'))]
    existing=json.loads(OUT.read_text()) if OUT.exists() else None
    if args.postgres: tables=reconstruct(groups)
    elif existing: tables=existing['tables']
    else: raise AssertionError('Inventory missing; run --postgres --write')
    validate_tables(tables,groups,mods)
    data={'schema_version':1,'source_commit':'af7ab57ead44304b89bbd47dabeeba45b761ce16','evidence':'Isolated PostgreSQL 16 fresh-schema replay, not deployed/runtime observation','migration_masters':masters,'migration_files':source_files,'models':mods,'tables':tables,'system_tables':system_tables(groups)}
    if args.write: OUT.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    elif data!=existing:
        for key in data:
            if data[key]!=existing.get(key): print('MISMATCH:',key,file=sys.stderr)
        raise AssertionError('Inventory differs from independent sources/reconstructed schema; inspect changes before --write')
    target_path=ROOT/'docs/b2b/schemas/target-model.json'
    target_result=verify_target(json.loads(target_path.read_text()),args.target_postgres) if target_path.exists() else {}
    print(json.dumps({**target_result,'status':'PASS','mode':'postgres-replay' if args.postgres else 'source-model-coverage','services':len(groups),'sql_files':len(source_files),'application_tables':len(tables),'system_tables':len(data['system_tables']),'columns':sum(len(t['columns']) for t in tables),'constraints':sum(len(t['constraints']) for t in tables),'indexes':sum(len(t['indexes']) for t in tables),'mapped_models':len(mods)},ensure_ascii=False))
if __name__=='__main__':
    try: main()
    except (AssertionError,RuntimeError) as e: print(str(e),file=sys.stderr);sys.exit(1)
