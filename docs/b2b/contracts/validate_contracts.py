#!/usr/bin/env python3
"""Validate B2B blueprint, not runtime behavior. Needs openapi-spec-validator + PyYAML."""
import json,re
from pathlib import Path
import yaml
from openapi_spec_validator import validate_spec
from openapi_schema_validator import OAS30Validator
P=Path(__file__).resolve().parent
spec=yaml.safe_load((P/'b2b-openapi.yaml').read_text());validate_spec(spec)
ops=[]
for path,methods in spec['paths'].items():
 for method,op in methods.items():
  ops.append(op['operationId'])
  assert op['x-policy'] and op['x-implementation-tas']
  names={p['name'] for p in op.get('parameters',[]) if p.get('in')=='path'}
  assert names==set(re.findall(r'{([^}]+)}',path)),(path,names)
  if method!='get': assert any(x.get('$ref','').endswith('/IdempotencyKey') for x in op.get('parameters',[]))
assert len(ops)==len(set(ops))
for n,s in spec['components']['schemas'].items():
 if s.get('type')=='object':assert s.get('additionalProperties') is False,n
proto=(P/'b2b.proto').read_text();rpc=re.findall(r'\brpc\s+(\w+)\(',proto)
for ident in ops:assert ident[0].upper()+ident[1:] in rpc,ident
uid='00000000-0000-4000-8000-000000000001'
fixtures=[
 ('Money',{'amountMinor':'2000','currency':'EUR','exponent':2},True),
 ('Money',{'amountMinor':'2000','currency':'EUR','exponent':1},False),
 ('Money',{'amountMinor':20.0,'currency':'EUR','exponent':2},False),
 ('Money',{'amountMinor':'2000','currency':'EUR','exponent':2,'secret':'x'},False),
 ('QuotaLimit',{'kind':'SEAT','unlimited':False,'limit':'5'},True),
 ('QuotaLimit',{'kind':'TASK_LIFETIME','unlimited':True},True),
 ('QuotaLimit',{'kind':'TASK_LIFETIME','unlimited':True,'limit':'50'},False),
 ('AdminScope',{'kind':'PLATFORM'},True),
 ('AdminScope',{'kind':'TENANT_SET','tenantIds':[uid]},True),
 ('AdminScope',{'kind':'TENANT_SET','tenantIds':[]},False),
 ('AdminScope',{'kind':'PLATFORM','tenantIds':[]},False),
 ('UpdateFlagRequest',{},False),
 ('UpdateFlagRequest',{'booleanValue':True,'integerValue':'1'},False),
 ('UpdateFlagRequest',{'booleanValue':True},True),
 ('AcceptInvitationRequest',{'token':'a'*32},True),
 ('AcceptInvitationRequest',{'token':'a'*32,'displayName':'Demo'},False),
 ('ReserveQuotaRequest',{'operationId':uid,'kind':'TASK_LIFETIME','quantity':'1','resourceId':uid},False),
 ('ReserveQuotaRequest',{'operationId':uid,'kind':'PROJECT','quantity':'0','resourceId':uid},False),
 ('ReserveQuotaRequest',{'operationId':uid,'kind':'PROJECT','quantity':'1','resourceId':uid},True),
 ('AutopayConsentRequest',{'consent':True,'confirmed':True},False),
 ('AutopayConsentRequest',{'consent':False,'confirmed':True},True),
 ('AdminGrantRequest',{'kind':'PAT','name':'ops','scope':{'kind':'TENANT_SET','tenantIds':[uid]},'level':'MODIFY','manageGlobalAccounts':True,'expiresInDays':7},False),
 ('AdminGrantRequest',{'kind':'PAT','name':'ops','scope':{'kind':'PLATFORM'},'level':'MODIFY','manageGlobalAccounts':True,'expiresInDays':7},True),
 ('AdminGrantRequest',{'kind':'OAUTH','name':'ops','scope':{'kind':'PLATFORM'},'level':'READ','manageGlobalAccounts':False,'expiresInDays':7},False),
 ('RefundRequest',{'paymentId':uid,'reason':'Request full return','reasonCode':'SERVICE_FAILURE','confirmed':True,'amount':'10'},False),
]
money={'amountMinor':'2000','currency':'EUR','exponent':2}
exact={'providerAccountId':'configured-bank-001','bankReference':'bank-001','amount':money,'evidenceReference':'evidence-001','occurredPrecision':'EXACT','occurredAt':'2026-11-02T10:00:00Z','payerRef':'verified-payer-001'}
day={k:v for k,v in exact.items() if k!='occurredAt'}
day.update(occurredPrecision='DAY',occurredNotBefore='2026-11-02T00:00:00Z',occurredBefore='2026-11-03T00:00:00Z')
def without(value,*names):return {k:v for k,v in value.items() if k not in names}
fixtures += [
 ('RecordWireReceiptRequest',exact,True),
 ('RecordWireReceiptRequest',dict(exact,occurredAt=None),False),
 ('RecordWireReceiptRequest',day,True),
 ('RecordWireReceiptRequest',dict(day,occurredAt=None),True),
 ('RecordWireReceiptRequest',dict(day,occurredAt='2026-11-02T00:00:00Z'),False),
 ('RecordWireReceiptRequest',without(day,'occurredBefore'),False),
 ('RecordWireReceiptRequest',dict(exact,occurredNotBefore='2026-11-02T00:00:00Z'),False),
 ('RecordWireReceiptRequest',without(exact,'payerRef'),False),
 ('RecordWireReceiptRequest',dict(exact,amount=dict(money,currency='USD')),False),
]
refund={'id':uid,'tenantId':uid,'paymentId':uid,'kind':'SUBSCRIPTION_FULL','state':'REQUESTED','amount':money,'requestedAt':'2026-11-02T10:00:00Z','version':'1','reasonCode':'SERVICE_FAILURE'}
fixtures += [
 ('Refund',refund,True),
 ('Refund',dict(refund,reasonCode='EXCESS'),False),
 ('Refund',dict(refund,kind='UNALLOCATED_RETURN',reasonCode='EXCESS'),True),
 ('Refund',dict(refund,kind='UNALLOCATED_RETURN'),False),
]
mandate={'id':uid,'ownerId':uid,'state':'DISABLED','version':'1','ownerVersion':'1'}
fixtures += [
 ('AutopayMandate',mandate,True),
 ('AutopayMandate',without(mandate,'ownerVersion'),False),
 ('AutopayMandate',dict(mandate,state='CONSENTED'),False),
]
evidence={k:day[k] for k in ['occurredPrecision','occurredNotBefore','occurredBefore','payerRef']}
evidence.update(evidenceRef='evidence-002',digest='a'*64,confirmed=True)
fixtures += [
 ('FinancialEvidenceRefinementRequest',evidence,True),
 ('FinancialEvidenceRefinementRequest',dict(evidence,amount=money),False),
 ('FinancialEvidenceRefinementRequest',dict(evidence,verifiedBy=uid),False),
 ('FinancialEvidenceRefinementRequest',dict(evidence,confirmed=False),False),
]
unmatched=without(dict(day,evidenceDigest='b'*64),'payerRef')
fixtures += [
 ('RecordUnmatchedWireReceiptRequest',unmatched,True),
 ('RecordUnmatchedWireReceiptRequest',dict(unmatched,tenantId=uid),False),
 ('AssignUnmatchedWireReceiptRequest',{'invoiceId':uid,'evidenceReference':'evidence-003','evidenceDigest':'b'*64,'confirmed':True},False),
 ('AssignUnmatchedWireReceiptRequest',{'invoiceId':uid,'payerRef':'verified-payer','evidenceReference':'evidence-003','evidenceDigest':'b'*64,'confirmed':True},True),
]
for name,value,valid in fixtures:
 schema={'$ref':'#/components/schemas/'+name,'components':spec['components']}
 # Resolve nested refs so positive/negative examples validate complete wire DTOs.
 errors=list(OAS30Validator(schema,format_checker=OAS30Validator.FORMAT_CHECKER).iter_errors(value));assert bool(errors)!=valid,(name,value,errors)
# Normative temporal examples, not evidence that billing runtime implements these rules.
from datetime import datetime
def classify_occurrence(value,deadline):
 def utc(v):
  assert v.endswith('Z'), 'UTC evidence required'
  return datetime.fromisoformat(v[:-1]+'+00:00')
 boundary=utc(deadline)
 if value['occurredPrecision']=='EXACT':
  return 'TIMELY' if utc(value['occurredAt'])<=boundary else 'LATE'
 lo,hi=utc(value['occurredNotBefore']),utc(value['occurredBefore'])
 if hi<=lo:raise ValueError('invalid occurrence interval')
 if hi<=boundary:return 'TIMELY'
 if lo>boundary:return 'LATE'
 return 'NEEDS_EVIDENCE'
temporal_cases=[
 (exact,'2026-11-02T10:00:00Z','TIMELY'),
 (exact,'2026-11-02T09:59:59Z','LATE'),
 (day,'2026-11-03T00:00:00Z','TIMELY'),
 (day,'2026-11-01T23:59:59Z','LATE'),
 (day,'2026-11-02T12:00:00Z','NEEDS_EVIDENCE'),
 (day,'2026-11-02T00:00:00Z','NEEDS_EVIDENCE'),
]
for value,deadline,expected in temporal_cases:assert classify_occurrence(value,deadline)==expected
try:classify_occurrence(dict(day,occurredBefore=day['occurredNotBefore']),'2026-11-03T00:00:00Z')
except ValueError:pass
else:raise AssertionError('empty interval accepted')
inventory=json.loads((P.parent/'inventory/api-operations.json').read_text())
for r in inventory['operations']:
 assert r['source']['baseline']==inventory['baseline']
 assert r.get('handler'),r
 for src in [r['source']]+[h['source'] for h in r['handler']]:
  file=P.parents[2]/src['path'];assert file.is_file(),src
  assert 0<src['line']<=len(file.read_text().splitlines()),src
print(json.dumps({'openapiPaths':len(spec['paths']),'openapiOperations':len(ops),'schemas':len(spec['components']['schemas']),'targetRpcs':len(rpc),'schemaCases':len(fixtures),'temporalCases':len(temporal_cases)+1,'baselineCounts':inventory['counts']},indent=2))
