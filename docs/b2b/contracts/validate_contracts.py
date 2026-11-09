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
# Pause/resume are versioned commands; DELETE deliberately has no payload.
pause_path=spec['paths']['/api/v1/tenants/{tenantId}/pause']
for method,operation in [('put','pauseTenant'),('delete','resumeTenant')]:
 op=pause_path[method]
 assert op['operationId']==operation
 refs={p.get('$ref') for p in op['parameters']}
 for parameter in ['IdempotencyKey','IfMatch']:
  assert '#/components/parameters/'+parameter in refs
  assert spec['components']['parameters'][parameter]['required'] is True
 assert {'412','428'} <= set(op['responses'])
assert pause_path['put']['requestBody']['required'] is True
assert pause_path['put']['requestBody']['content']['application/json']['schema']['$ref'].endswith('/ConfirmedAction')
assert 'requestBody' not in pause_path['delete']
# Immutable wire receipts have no entity version to read; concurrency is guarded by
# source/invoice locks and durable business deduplication, not an invented If-Match.
wire_confirm=spec['paths']['/api/v1/admin/billing/wire-receipts/{paymentId}/confirm']['post']
wire_refs={p.get('$ref') for p in wire_confirm['parameters']}
assert '#/components/parameters/IdempotencyKey' in wire_refs
assert '#/components/parameters/IfMatch' not in wire_refs
assert not {'412','428'} & set(wire_confirm['responses'])
assert wire_confirm['requestBody']['required'] is True
assert wire_confirm['requestBody']['content']['application/json']['schema']['$ref'].endswith('/ConfirmWireReceiptRequest')
wire_payload=spec['components']['schemas']['ConfirmWireReceiptRequest']
assert {'confirmed','evidenceReference'} <= set(wire_payload['required'])
assert wire_payload['properties']['confirmed']['enum']==[True]
# Every newly specified precondition has an authorized readable source and a
# required decimal version, including first autopay consent and account deletion.
by_operation={op['operationId']:(method,op) for methods in spec['paths'].values() for method,op in methods.items()}
for command,read_operation,schema in [('setAutopayConsent','getSubscription','Subscription'),
                                      ('changeGlobalAccount','getGlobalAccount','AdminAccount'),
                                      ('adminDeleteAccount','getGlobalAccount','AdminAccount')]:
 _,op=by_operation[command]
 assert op['x-version-source']=={'operationId':read_operation,'schema':schema,'property':'version'}
 read_method,read_op=by_operation[read_operation]
 assert read_method=='get' and 'requestBody' not in read_op
 assert read_op['responses']['200']['content']['application/json']['schema']['$ref']=='#/components/schemas/'+schema
 versioned_schema=spec['components']['schemas'][schema]
 assert 'version' in versioned_schema['required']
 assert versioned_schema['properties']['version']['type']=='string'
 assert '#/components/parameters/IfMatch' in {p.get('$ref') for p in op['parameters']}
 assert {'412','428'} <= set(op['responses'])
account_read=by_operation['getGlobalAccount'][1]
for name in ['getGlobalAccount','changeGlobalAccount','adminDeleteAccount']:
 assert by_operation[name][1]['x-policy']=='ADMIN_GLOBAL_ACCOUNTS_WRITE'
 assert by_operation[name][1]['security']==[{'bearerAuth':[]}]
assert account_read['x-implementation-tas']==['TAS-485']
assert set(spec['components']['schemas']['AdminAccount']['properties'])=={'userId','role','state','version'}
assert set(spec['components']['schemas']['AdminAccount']['properties']['state']['enum'])=={'ACTIVE','BLOCKED','DELETED','INVITED','LOCKED'}
account_delete=spec['components']['schemas']['AdminDeleteAccountRequest']
assert 'expectedAccountVersion' in account_delete['required']
assert account_delete['properties']['expectedAccountVersion']['pattern']==spec['components']['schemas']['AdminAccount']['properties']['version']['pattern']
for n,s in spec['components']['schemas'].items():
 if s.get('type')=='object':assert s.get('additionalProperties') is False,n
proto=(P/'b2b.proto').read_text();rpc=re.findall(r'\brpc\s+(\w+)\(',proto)
for ident in ops:assert ident[0].upper()+ident[1:] in rpc,ident
uid='00000000-0000-4000-8000-000000000001'
fixtures=[
 ('AdminAccount',{'userId':uid,'role':'USER','state':'ACTIVE','version':'1'},True),
 ('AdminAccount',{'userId':uid,'role':'USER','state':'ACTIVE','version':'0'},False),
 ('AdminAccount',{'userId':uid,'role':'USER','state':'INVITED','version':'1'},True),
 ('AdminAccount',{'userId':uid,'role':'USER','state':'LOCKED','version':'2'},True),
 ('AdminDeleteAccountRequest',{'confirmed':True,'reason':'verified request','expectedAccountVersion':'1'},True),
 ('AdminDeleteAccountRequest',{'confirmed':True,'reason':'verified request','expectedAccountVersion':'0'},False),
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
 ('PlatformFlag',{'key':'MCP','type':'BOOLEAN','booleanValue':True,'version':'1','effectiveAt':'2026-11-05T09:00:00Z','owner':'mcp-server','defaultBooleanValue':True},True),
 ('PlatformFlag',{'key':'MCP','type':'BOOLEAN','booleanValue':True,'integerValue':'1','version':'1','effectiveAt':'2026-11-05T09:00:00Z','owner':'mcp-server'},False),
 ('PlatformFlag',{'key':'MCP','type':'BOOLEAN','booleanValue':True,'version':'1','effectiveAt':'2026-11-05T09:00:00Z','owner':'mcp-server','secret':'x'},False),
 ('PlatformFlag',{'key':'WIRE_RECONCILIATION_TARGET_SECONDS','type':'DURATION_SECONDS','integerValue':'86400','version':'2','effectiveAt':'2026-11-05T09:00:00Z','owner':'billing-service','defaultIntegerValue':'86400','minimumValue':'1','maximumValue':'31536000'},True),
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
refund={'sourceMethod':'PSP','id':uid,'tenantId':uid,'paymentId':uid,'kind':'SUBSCRIPTION_FULL','state':'REQUESTED','amount':money,'requestedAt':'2026-11-02T10:00:00Z','version':'1','reasonCode':'SERVICE_FAILURE'}
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
final_partial='LOST_PARTIAL_COVERAGE_REVOKED'
outcome={'outcome':final_partial,'evidenceReference':'verified-resolution','externalReference':'provider-dispute','confirmed':True,'confirmedMoneyFactId':uid}
dispute={'id':uid,'tenantId':uid,'paymentId':uid,'state':'LOST','openedAt':'2026-11-02T10:00:00Z','resolvedAt':'2026-11-03T10:00:00Z','version':'2','finalOutcome':final_partial,'confirmedMoneyFactId':uid}
fixtures += [
 ('DisputeOutcomeRequest',outcome,True),
 ('DisputeOutcomeRequest',dict(outcome,outcome='PARTIAL_MANUAL_REVIEW'),False),
 ('DisputeOutcomeRequest',without(outcome,'confirmedMoneyFactId'),False),
 ('DisputeOutcomeRequest',dict(outcome,confirmed=False),False),
 ('DisputeOutcomeRequest',dict(outcome,refundAmount=money),False),
 ('Dispute',dispute,True),
 ('Dispute',dict(dispute,state='NEEDS_REVIEW'),False),
 ('Dispute',dict(dispute,finalOutcome='WON'),False),
 ('Dispute',without(dispute,'resolvedAt'),False),
 ('Dispute',without(dispute,'confirmedMoneyFactId'),False),
 ('Dispute',dict(without(dispute,'confirmedMoneyFactId'),state='WON',finalOutcome='WON'),True),
]
sla={'businessDays':5,'feeBearer':'TASKA','countingRule':'T_PLUS_5_BUSINESS_DATES_SAME_LOCAL_TIME','timezone':'Europe/Madrid','calendarDigest':'f'*64,'excludeEvidenceDate':True,'dstGapRule':'NEXT_VALID_INSTANT','dstOverlapRule':'LATER_OFFSET'}
wire_refund=dict(refund,sourceMethod='WIRE',feeBearer='TASKA')
wire_clock=dict(wire_refund,evidenceCompletedAt='2026-11-03T09:30:00Z',businessCalendarId='SANDBOX_FIXTURE_ES',calendarVersion='fixture-1',slaPolicyVersion='WIRE_RETURN_ES_V1',slaSnapshot=sla,decisionDueAt='2026-11-10T09:30:00Z',sendDueAt='2026-11-10T09:30:00Z')
fixtures += [
 ('Refund',wire_refund,True),
 ('Refund',without(wire_refund,'feeBearer'),False),
 ('Refund',dict(wire_refund,feeBearer='PAYER'),False),
 ('Refund',dict(wire_refund,evidenceCompletedAt='2026-11-03T09:30:00Z'),False),
 ('Refund',wire_clock,True),
 ('Refund',dict(wire_clock,submittedAt='2026-11-12T09:30:00Z'),True),
 ('Refund',without(wire_clock,'sendDueAt'),False),
 ('Refund',dict(wire_clock,sourceMethod='PSP'),False),
 ('Refund',dict(wire_refund,submittedAt='2026-11-03T12:30:00Z'),False),
 ('RefundSlaSnapshot',dict(sla,businessDays=10),False),
 ('RefundSlaSnapshot',dict(sla,feeBearer='PAYER'),False),
 ('RefundSlaSnapshot',dict(sla,excludeEvidenceDate=False),False),
]
assert spec['components']['schemas']['Dispute']['properties']['finalOutcome']['enum']==spec['components']['schemas']['DisputeOutcomeRequest']['properties']['outcome']['enum']
assert 'reserved "OUTCOME_PARTIAL_MANUAL_REVIEW"' in proto
for name,value,valid in fixtures:
 schema={'$ref':'#/components/schemas/'+name,'components':spec['components']}
 # Resolve nested refs so positive/negative examples validate complete wire DTOs.
 errors=list(OAS30Validator(schema,format_checker=OAS30Validator.FORMAT_CHECKER).iter_errors(value));assert bool(errors)!=valid,(name,value,errors)
# Normative temporal examples, not evidence that billing runtime implements these rules.
from datetime import datetime,timedelta,timezone
from zoneinfo import ZoneInfo
from copy import deepcopy
from calendar import monthrange
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
# Executable normative references for 2026-11-03 decisions, not production service tests.
billing_scenarios=[]
def final_partial_effect(state,payment_id,fact_id):
 result=deepcopy(state)
 fact=result['facts'][fact_id]
 if not fact['confirmed'] or fact['paymentId']!=payment_id or not 0<fact['lostMinor']<fact['paidMinor']:
  raise ValueError('verified partial loss of this payment required')
 if fact_id in result['appliedFacts']:return result
 for period in result['periods']:
  if period['paymentId']==payment_id:period['state']='REVOKED'
 result['autopay']='DISABLED'
 result['appliedFacts'].append(fact_id)
 # No new monetary fact or outgoing return is produced by recording terminal outcome.
 return result
state={'periods':[{'id':'period-a','paymentId':'payment-a','state':'GRANTED'},{'id':'period-b','paymentId':'payment-b','state':'GRANTED'}],'facts':{'loss-a':{'paymentId':'payment-a','confirmed':True,'lostMinor':300,'paidMinor':2000},'loss-duplicate':{'paymentId':'duplicate-payment','confirmed':True,'lostMinor':300,'paidMinor':2000}},'autopay':'CONSENTED','appliedFacts':[],'outgoingReturns':[],'independentRestrictions':['OWNER_PAUSE']}
result=final_partial_effect(state,'payment-a','loss-a')
assert result['periods'][0]['state']=='REVOKED' and result['periods'][1]==state['periods'][1]
assert result['facts']==state['facts'] and result['outgoingReturns']==[] and result['independentRestrictions']==['OWNER_PAUSE'] and result['autopay']=='DISABLED'
billing_scenarios.append('revoke-linked-only-preserve-independent-no-new-money')
assert final_partial_effect(result,'payment-a','loss-a')==result
billing_scenarios.append('duplicate-terminal-fact-has-no-second-effect')
assert final_partial_effect(state,'duplicate-payment','loss-duplicate')['periods']==state['periods']
billing_scenarios.append('duplicate-historical-payment-has-no-linked-coverage')
try:final_partial_effect(state,'payment-b','loss-a')
except ValueError:pass
else:raise AssertionError('cross-payment loss accepted')
billing_scenarios.append('wrong-payment-evidence-rejected')
for invalid_fact in [{'confirmed':False},{'lostMinor':2000}]:
 invalid=deepcopy(state);invalid['facts']['loss-a'].update(invalid_fact)
 try:final_partial_effect(invalid,'payment-a','loss-a')
 except ValueError:pass
 else:raise AssertionError('unverified or nonpartial loss accepted')
 assert invalid['periods']==state['periods'] and invalid['outgoingReturns']==[]
 billing_scenarios.append('reject-invalid-partial-fact-'+next(iter(invalid_fact)))
def new_full_month(source,psp_success=None,wire_confirmed=None):
 start=psp_success if source=='PSP' else wire_confirmed
 if start is None:raise ValueError('confirmed payment required')
 start=datetime.fromisoformat(start.replace('Z','+00:00'))
 year,month=(start.year+1,1) if start.month==12 else (start.year,start.month+1)
 return start,start.replace(year=year,month=month,day=min(start.day,monthrange(year,month)[1]))
psp_start,psp_end=new_full_month('PSP',psp_success='2027-01-31T10:00:00Z')
assert psp_start.isoformat()=='2027-01-31T10:00:00+00:00' and psp_end.isoformat()=='2027-02-28T10:00:00+00:00'
billing_scenarios.append('new-psp-payment-new-full-calendar-month')
wire_start,wire_end=new_full_month('WIRE',psp_success='2027-01-31T10:00:00Z',wire_confirmed='2027-02-02T12:00:00Z')
assert wire_start.isoformat()=='2027-02-02T12:00:00+00:00' and wire_end.isoformat()=='2027-03-02T12:00:00+00:00'
billing_scenarios.append('wire-month-starts-at-admin-confirmation')
try:new_full_month('WIRE',psp_success='2027-01-31T10:00:00Z')
except ValueError:pass
else:raise AssertionError('wire coverage without confirmation')
billing_scenarios.append('receipt-or-client-success-does-not-activate-wire')
MADRID=ZoneInfo('Europe/Madrid')
def resolve_madrid(local):
 probe=local
 while True:
  candidates=[]
  for fold in (0,1):
   aware=probe.replace(tzinfo=MADRID,fold=fold)
   utc=aware.astimezone(timezone.utc)
   if utc.astimezone(MADRID).replace(tzinfo=None)==probe:candidates.append(utc)
  if candidates:return max(candidates) # Later instant/offset at overlap.
  probe=probe.replace(second=0,microsecond=0)+timedelta(minutes=1) # First valid instant after a gap.
def wire_due(completed,nonworking):
 local=datetime.fromisoformat(completed.replace('Z','+00:00')).astimezone(MADRID)
 candidate=local.date();count=0
 while count<5:
  candidate+=timedelta(days=1)
  if candidate.weekday()<5 and candidate.isoformat() not in nonworking:count+=1
 return resolve_madrid(datetime.combine(candidate,local.time().replace(tzinfo=None)))
# Named fixture calendars only; these dates do not claim to be the seller's live bank calendar.
assert wire_due('2026-11-02T10:00:00Z',set()).isoformat()=='2026-11-09T10:00:00+00:00'
billing_scenarios.append('sla-excludes-completion-day-and-weekend')
assert wire_due('2026-11-03T09:30:00Z',{'2026-11-04'}).isoformat()=='2026-11-11T09:30:00+00:00'
billing_scenarios.append('sla-versioned-fixture-holiday-excluded')
assert wire_due('2027-03-26T10:00:00Z',set()).isoformat()=='2027-04-02T09:00:00+00:00'
billing_scenarios.append('sla-keeps-madrid-wall-time-across-dst')
assert resolve_madrid(datetime(2027,3,28,2,30)).isoformat()=='2027-03-28T01:00:00+00:00'
assert resolve_madrid(datetime(2027,10,31,2,30)).isoformat()=='2027-10-31T01:30:00+00:00'
billing_scenarios.append('sla-gap-next-valid-overlap-later-offset')
def check_frozen_clock(record):
 assert record['decisionDueAt']==record['sendDueAt']
 assert record['feeBearer']=='TASKA' and record['slaSnapshot']['feeBearer']=='TASKA'
check_frozen_clock(wire_clock)
try:check_frozen_clock(dict(wire_clock,sendDueAt='2026-11-17T09:30:00Z'))
except AssertionError:pass
else:raise AssertionError('new five-day send clock accepted')
assert dict(wire_clock,decidedAt='2026-11-09T09:30:00Z')['sendDueAt']==wire_clock['sendDueAt']
billing_scenarios.append('one-frozen-decision-and-send-deadline-no-reset')
inventory=json.loads((P.parent/'inventory/api-operations.json').read_text())
for r in inventory['operations']:
 assert r['source']['baseline']==inventory['baseline']
 assert r.get('handler'),r
 for src in [r['source']]+[h['source'] for h in r['handler']]:
  file=P.parents[2]/src['path'];assert file.is_file(),src
  assert 0<src['line']<=len(file.read_text().splitlines()),src
print(json.dumps({'openapiPaths':len(spec['paths']),'openapiOperations':len(ops),'schemas':len(spec['components']['schemas']),'targetRpcs':len(rpc),'schemaCases':len(fixtures),'temporalCases':len(temporal_cases)+1,'billingScenarioCases':len(billing_scenarios),'baselineCounts':inventory['counts']},indent=2))
