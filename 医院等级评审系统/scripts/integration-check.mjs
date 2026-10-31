import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const base = process.env.DEMO_BASE_URL ?? 'http://127.0.0.1:18173';
const output = fileURLToPath(new URL('../.runtime/', import.meta.url));
let checks = 0;
async function request(path, { method = 'GET', body, role = 'quality', expected = 200 } = {}) {
  const response = await fetch(base + '/api' + path, {
    method, headers: { 'X-Demo-Role': role, ...(body instanceof FormData || body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  const payload = await response.json();
  assert.equal(response.status, expected, `${method} ${path}: ${JSON.stringify(payload)}`);
  return payload;
}
function passed(name) { checks++; console.log(`PASS ${name}`); }
await mkdir(output, { recursive: true });
try {
  assert.equal((await request('/health')).demo, true);
  await request('/demo/reset', { method: 'POST', body: { confirm: 'RESET_DEMO' } });
  let state = await request('/workspace');
  assert.equal(state.meta.demo, true);
  assert.equal(state.indicators.length, 14);
  assert.equal(state.prerequisites.length, 30);
  passed('health, seeded indicators and prerequisites');

  const patient = state.indicators.find(x => x.sourceTables.includes('visit_discharge') && x.denominator > 0);
  assert.ok(patient, 'A patient-based indicator must be seeded');
  const members = await request(`/indicators/${patient.id}/members?dept=ALL&role=denominator&page=1&size=20`);
  assert.equal(members.total, patient.denominator);
  assert.ok(members.items.length > 0 && members.items.length <= 20);
  assert.ok(members.items.every(x => x.inpatientNo.includes('*')));
  await request(`/indicators/${patient.id}/members`, { role: 'leader', expected: 403 });
  assert.equal((await request('/workspace', {role:'leader'})).reconciliations.length, 0);
  assert.equal((await request('/workspace', {role:'information'})).reconciliations.length, 0);
  await request('/manual-values', { method: 'POST', role: 'leader', body: {}, expected: 403 });
  passed('patient cardinality, masking and server role limits');

  const [first, second] = state.indicators.filter(x => x.collectMode === 'auto').slice(0, 2);
  for (const [indicator, value] of [[first, 2.25], [second, 3.5]]) {
    const manual = await request('/manual-values', { method: 'POST', body: { indicatorId: indicator.id, deptCode: 'ALL', period: '2026-09', value, note: '自动化集成测试：演示补录' } });
    await request(`/manual-values/${manual.id}/review`, { method: 'POST', body: { decision: 'approved', note: '核对合成数据后通过' } });
    await request(`/manual-values/${manual.id}/review`, { method: 'POST', body: { decision: 'approved', note: '重复操作' }, expected: 409 });
  }
  state = await request('/workspace');
  assert.equal(state.indicators.find(x => x.id === first.id).value, 2.25);
  assert.equal(state.indicators.find(x => x.id === second.id).value, 3.5);
  assert.equal(state.indicators.find(x => x.id === first.id).autoValue, first.autoValue);
  assert.equal(state.indicators.find(x => x.id === first.id).source, 'manual');
  passed('two manual overlays coexist, automatic value retained, duplicate review rejected');

  const run = await request('/score-runs', { method: 'POST', body: { cycleStart: 2023, cycleEnd: 2026 } });
  state = await request('/workspace');
  const score = state.scoreRuns.find(x => x.id === run.id);
  assert.ok(score && score.trial);
  const originalScore = JSON.stringify(score.items);
  const beforeReport = await fetch(base + `/api/reports/${run.id}`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.equal(beforeReport.status, 200);
  const beforeBytes = Buffer.from(await beforeReport.arrayBuffer());
  assert.equal(beforeBytes.subarray(0, 2).toString(), 'PK');
  assert.ok(beforeBytes.includes(Buffer.from('word/document.xml')));
  await writeFile(resolve(output, 'qa-report-before.docx'), beforeBytes);
  passed('trial score and real DOCX package');

  const editable = state.prerequisites.find(x => !x.systemGenerated);
  const attachment = new FormData();
  attachment.append('ownerType', 'prereq'); attachment.append('ownerId', editable.id);
  attachment.append('file', new Blob(['仅供演示的测试佐证'], { type: 'text/plain' }), 'integration-evidence.txt');
  const evidence = await request('/evidence', { method: 'POST', body: attachment });
  assert.ok(evidence.id);
  await request(`/prerequisites/${editable.id}`, { method: 'PUT', body: { compliance: 'non_compliant', workflow: 'accepted', statement: '演示：复核认可不符合结论' } });
  const deferred = await request('/score-runs', { method: 'POST', body: { cycleStart: 2023, cycleEnd: 2026 } });
  state = await request('/workspace');
  assert.equal(state.scoreRuns.find(x => x.id === deferred.id).grade, 'deferred');
  assert.equal(JSON.stringify(state.scoreRuns.find(x => x.id === run.id).items), originalScore);
  const afterReport = await fetch(base + `/api/reports/${run.id}`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.equal(afterReport.status, 200);
  await writeFile(resolve(output, 'qa-report-after.docx'), Buffer.from(await afterReport.arrayBuffer()));
  passed('evidence upload, accepted noncompliance defers score, historical score unchanged');

  const blocked = state.policies.find(x => x.impacts.includes('scoring_rule'));
  if (blocked) {
    const response = await fetch(base + `/api/policies/${blocked.id}/confirm`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Demo-Role': 'quality' }, body: '{}' });
    assert.ok(response.status === 400 || response.status === 409);
  }
  const sample = await request('/audit-samples', { method: 'POST', body: { period: '2026-09' } });
  state = await request('/workspace');
  const savedSample = state.auditSamples.find(x => x.id === sample.id);
  assert.ok(savedSample && savedSample.sampleCount > 0);
  assert.equal(savedSample.errorRate, savedSample.errorCount / savedSample.sampleCount);
  assert.ok(state.auditLogs.length > 0);
  passed('policy blocking, independent clause sample and audit trail');
  const improvementId = 'im-initial-1';
  await request(`/improvements/${improvementId}`, {method:'PATCH',body:{status:'assigned'}});
  await request(`/improvements/${improvementId}`, {method:'PATCH',body:{status:'doing',actionText:'合成验收措施与复核'}});
  const proof = new FormData();
  proof.append('ownerType','improvement'); proof.append('ownerId',improvementId);
  proof.append('file',new Blob(['合成改进验收证据'],{type:'text/plain'}),'improvement-proof.txt');
  await request('/evidence',{method:'POST',body:proof});
  await request(`/improvements/${improvementId}`, {method:'PATCH',body:{status:'evidence_ready'}});
  await request(`/improvements/${improvementId}`, {method:'PATCH',body:{status:'verified'}});
  await request(`/improvements/${improvementId}`, {method:'PATCH',body:{status:'closed',closeMode:'not_recovered',closeNote:'演示验收，尚未恢复，保留整改说明'}});
  const reopened = await request(`/improvements/${improvementId}`, {method:'PATCH',body:{status:'reopen'}});
  assert.equal(reopened.status,'opened');
  passed('improvement evidence, sequential closure and reopening');

  // ---------- v0.2 生产形态链路 ----------
  async function v2request(path, { method = 'GET', body, role = 'quality', expected = 200 } = {}) {
    const response = await fetch(base + '/api/v2' + path, {
      method,
      headers: { ...(role ? { 'X-Demo-Role': role } : {}), ...(body instanceof FormData || body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    });
    const payload = await response.json().catch(() => ({}));
    assert.equal(response.status, expected, `v2 ${method} ${path}: ${JSON.stringify(payload)}`);
    return payload;
  }

  const me = await v2request('/me');
  assert.equal(me.hospitalId, 'H001');
  assert.deepEqual(me.roles, ['review_office']);
  await v2request('/me', { role: null, expected: 401 });
  passed('v2 identity context and unauthenticated rejection');

  const standards = await v2request('/standards');
  const version = standards.packages[0].versions.find(x => x.status === 'published');
  assert.ok(version, 'a published standard version is required');
  const tree = await v2request(`/standards/versions/${version.id}`);
  assert.equal(tree.counts.chapters, 5);
  assert.equal(tree.counts.clauses, 10);
  assert.equal(tree.counts.indicators, 14);
  await v2request(`/standards/versions/${version.id}/publish`, { method: 'POST', body: {}, expected: 409 });
  passed('v2 standard tree and published immutability');

  const requestKey = `integration-${Date.now()}`;
  const assigned = await v2request('/tasks/assign', {
    method: 'POST',
    body: { versionId: version.id, clauseCodes: ['CL-2.1'], deptCode: 'CARD', dueDate: '2026-12-31', priority: 'high', requestKey },
  });
  assert.equal(assigned.tasks.length, 1);
  const again = await v2request('/tasks/assign', {
    method: 'POST',
    body: { versionId: version.id, clauseCodes: ['CL-2.1'], deptCode: 'CARD', dueDate: '2026-12-31', priority: 'high', requestKey },
  });
  assert.equal(again.idempotent, true);
  assert.equal(again.tasks[0].id, assigned.tasks[0].id);
  passed('v2 task assignment idempotency');

  const taskId = assigned.tasks[0].id;
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'in_progress' }, role: 'department' });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'submitted' }, role: 'department' });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'accepted' }, role: 'department', expected: 403 });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'returned' }, expected: 400 });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'returned', comment: '材料缺少审批页' } });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'in_progress' }, role: 'department' });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'submitted' }, role: 'department' });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'accepted' } });
  await v2request(`/tasks/${taskId}`, { method: 'PATCH', body: { status: 'in_progress' }, expected: 409 });
  passed('v2 task state machine, department scope and terminal immutability');

  const upload = new FormData();
  upload.append('ownerType', 'task');
  upload.append('ownerId', taskId);
  upload.append('title', '集成检查材料');
  upload.append('materialType', '检查记录');
  upload.append('file', new Blob(['v2 evidence version one'], { type: 'text/plain' }), 'v2-evidence.txt');
  const firstVersion = await v2request('/evidence', { method: 'POST', body: upload });
  assert.equal(firstVersion.version.versionNo, 1);
  const duplicate = new FormData();
  duplicate.append('ownerType', 'task');
  duplicate.append('ownerId', taskId);
  duplicate.append('title', '集成检查材料');
  duplicate.append('materialType', '检查记录');
  duplicate.append('file', new Blob(['v2 evidence version one'], { type: 'text/plain' }), 'v2-evidence.txt');
  const repeated = await v2request('/evidence', { method: 'POST', body: duplicate });
  assert.equal(repeated.idempotent, true);
  assert.equal(repeated.version.id, firstVersion.version.id);
  const secondUpload = new FormData();
  secondUpload.append('ownerType', 'task');
  secondUpload.append('ownerId', taskId);
  secondUpload.append('title', '集成检查材料');
  secondUpload.append('materialType', '检查记录');
  secondUpload.append('file', new Blob(['v2 evidence version two'], { type: 'text/plain' }), 'v2-evidence.txt');
  const secondVersion = await v2request('/evidence', { method: 'POST', body: secondUpload });
  assert.equal(secondVersion.version.versionNo, 2);
  const itemId = secondVersion.item.id;
  await v2request(`/evidence/${itemId}/review`, { method: 'POST', body: { decision: 'approved', note: '集成检查通过' } });
  await v2request(`/evidence/${itemId}/review`, { method: 'POST', body: { decision: 'rejected' }, expected: 409 });
  const download = await fetch(base + `/api/v2/evidence/versions/${firstVersion.version.id}/download`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.equal(download.status, 200);
  assert.equal(await download.text(), 'v2 evidence version one');
  passed('v2 evidence versions, hash idempotency, review and old version download');

  const rejectedBatch = await v2request('/batches', {
    method: 'POST',
    body: { sourceCode: 'CSV-IMPORT', period: '2027-01', rows: [{ indicatorCode: 'i01', numerator: 2000, denominator: 1000, value: 120 }] },
  });
  assert.equal(rejectedBatch.batch.status, 'rejected');
  assert.ok(rejectedBatch.issues.some(issue => issue.severity === 'error'));
  await v2request(`/batches/${rejectedBatch.batch.id}/apply`, { method: 'POST', body: {}, expected: 409 });
  passed('v2 quality gate blocks invalid batch from being applied');

  const period = '2026-12';
  const validBatch = await v2request('/batches', {
    method: 'POST',
    body: {
      sourceCode: 'CSV-IMPORT',
      period,
      rows: [
        { indicatorCode: 'i01', numerator: 940, denominator: 2000, value: 0.47 },
        { indicatorCode: 'i02', numerator: 1100, denominator: 2000, value: 55 },
        { indicatorCode: 'i14', value: 8.0 },
      ],
    },
  });
  assert.equal(validBatch.batch.status, 'validated');
  await v2request(`/batches/${validBatch.batch.id}/apply`, { method: 'POST', body: {} });
  await v2request(`/batches/${validBatch.batch.id}/apply`, { method: 'POST', body: {}, expected: 409 });
  const replayed = await v2request(`/batches/${validBatch.batch.id}/replay`, { method: 'POST', body: {} });
  assert.equal(replayed.batch.replayOf, validBatch.batch.id);
  passed('v2 batch apply once, replay creates new batch');

  const runA = await v2request('/score-runs', { method: 'POST', body: { versionId: version.id, cycleStart: 2023, cycleEnd: 2026 } });
  assert.ok(runA.run.includedCount > 0);
  assert.ok(runA.items.length >= runA.run.includedCount);
  const report = await fetch(base + `/api/v2/score-runs/${runA.run.id}/report`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.equal(report.status, 200);
  const reportBytes = Buffer.from(await report.arrayBuffer());
  assert.equal(reportBytes.subarray(0, 2).toString(), 'PK');
  assert.ok(reportBytes.includes(Buffer.from('word/document.xml')));
  const rerun = await fetch(base + `/api/v2/score-runs/${runA.run.id}/report`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.ok(Buffer.from(await rerun.arrayBuffer()).equals(reportBytes), 'snapshot report must be byte-stable');
  const runB = await v2request('/score-runs', { method: 'POST', body: { versionId: version.id, cycleStart: 2023, cycleEnd: 2026 } });
  const comparison = await v2request(`/score-runs/${runA.run.id}/compare/${runB.run.id}`);
  assert.ok(Array.isArray(comparison.recovered) && Array.isArray(comparison.worsened));
  passed('v2 immutable snapshot, stable DOCX report and run comparison');

  const prereqs = await v2request('/prerequisites');
  assert.equal(prereqs.items.length, 30);
  assert.equal(prereqs.items[0].compliance, 'unknown', '未维护的前置不能默认符合');
  await v2request('/prerequisites/PRE-07', {
    method: 'PUT',
    body: { versionId: version.id, compliance: 'non_compliant', workflow: 'accepted', statement: '集成检查：条款不符合' },
  });
  await v2request('/prerequisites/SYS-TRUTH', {
    method: 'PUT',
    body: { versionId: version.id, compliance: 'compliant', workflow: 'accepted', statement: '试图改成符合' },
    expected: 409,
  });
  const auditSample = await v2request('/audit-samples', { method: 'POST', body: { period: '2026-12' } });
  assert.ok(auditSample.sampleCount > 0);
  assert.equal(auditSample.items.length, auditSample.sampleCount);
  const deferredRun = await v2request('/score-runs', {
    method: 'POST',
    body: { versionId: version.id, cycleStart: 2023, cycleEnd: 2026 },
  });
  assert.equal(deferredRun.run.grade, 'deferred');
  assert.ok(deferredRun.run.deferredReason.includes('前置不符合'), deferredRun.run.deferredReason);
  passed('v2 prerequisite conclusion, audit sample and deferral reason');

  const audit = await v2request('/audit-events?limit=100');  const actions = audit.items.map(item => item.action);
  assert.ok(actions.includes('分派条款任务'), 'audit must record assignment');
  assert.ok(actions.includes('上传材料版本'), 'audit must record evidence upload');
  assert.ok(actions.includes('导入数据批次'), 'audit must record batch import');
  assert.ok(audit.items.every(item => item.requestId), 'audit must carry request id');
  passed('v2 audit trail with request correlation id');


  // ---------- v3 数据中台链路 ----------
  async function v3request(path, { method = 'GET', body, role = 'quality', expected = 200 } = {}) {
    const response = await fetch(base + '/api/v3' + path, {
      method,
      headers: { ...(role ? { 'X-Demo-Role': role } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const payload = await response.json().catch(() => ({}));
    assert.equal(response.status, expected, `v3 ${method} ${path}: ${JSON.stringify(payload)}`);
    return payload;
  }

  const v3Defs = await v3request('/metric-definitions');
  assert.equal(v3Defs.items.length, 14);
  const v3Version = v3Defs.versionId;
  const i11Def = v3Defs.items.find(item => item.code === 'i11');
  assert.equal(i11Def.formulaType, 'drg_formula', 'i11 必须是受控 DRG 公式');
  passed('v3 metric definitions are versioned with controlled formulas');

  const v3Bad = await v3request('/ingestion/batches', {
    method: 'POST',
    body: { sourceCode: 'CSV-IMPORT', period: '2026-10', rows: [
      { code: 'i01', dept: 'CARD', num: 3000, den: 2000, unit: '%' },
      { code: 'unknown', dept: 'CARD', num: 1, den: 2, unit: '%' },
    ] },
  });
  assert.equal(v3Bad.batch.status, 'rejected');
  assert.ok(v3Bad.issues.every(issue => issue.rowNo > 0));
  await v3request(`/ingestion/batches/${v3Bad.batch.id}/apply`, { method: 'POST', body: {}, expected: 409 });
  const v3Replay = await v3request(`/ingestion/batches/${v3Bad.batch.id}/replay`, { method: 'POST', body: {} });
  assert.equal(v3Replay.batch.replayOf, v3Bad.batch.id);
  passed('v3 quality gate blocks bad batch, issues are line-locatable, replay creates new batch');

  const v3Good = await v3request('/ingestion/batches', {
    method: 'POST',
    body: { sourceCode: 'CSV-IMPORT', period: '2026-10', rows: [
      { code: 'i01', dept: 'CARD', num: 11, den: 2000, unit: '%' },
      { code: 'i11', dept: 'CARD', num: 400, den: 100, unit: '' },
      { code: 'i11', dept: 'SURG', num: 600, den: 300, unit: '' },
      { code: 'i12', dept: 'ALL', val: 860, unit: '张' },
    ] },
  });
  assert.equal(v3Good.batch.status, 'validated');
  await v3request(`/ingestion/batches/${v3Good.batch.id}/apply`, { method: 'POST', body: {} });
  const v3Results = await v3request('/metric-results?period=2026-10');
  const v3I11 = v3Results.items.find(item => item.metricCode === 'i11' && item.deptCode === 'ALL');
  assert.equal(v3I11.value, 2.5, 'DRG 必须按 Σ分子/Σ分母 聚合，不得退化为平均');
  assert.equal(v3I11.numerator, 1000);
  assert.equal(v3I11.denominator, 400);
  const v3I12 = v3Results.items.find(item => item.metricCode === 'i12' && item.deptCode === 'ALL');
  assert.equal(v3I12.value, 860, '年末快照取原值');
  passed('v3 batch applies, i11 weighted aggregation and i12 snapshot verified');

  const v3Lineage = await v3request(`/lineage/results/${v3I11.id}`);
  const v3Types = v3Lineage.nodes.map(node => node.type);
  assert.ok(v3Types.includes('source') && v3Types.includes('batch') && v3Types.includes('definition'));
  const v3Drill = await v3request(`/metric-results/${v3I11.id}/drilldown`);
  assert.ok(v3Drill.departments.length >= 2);
  assert.equal(v3Drill.members.masked, true);
  await v3request('/metric-results?period=2026-10', { role: 'department' }).then(scoped => {
    assert.ok(scoped.items.every(item => item.deptCode === 'CARD'), '科室角色只能看到本科室结果');
  });
  passed('v3 drilldown masked, lineage traceable, department scope enforced');

  const v3Open = await v3request('/alerts?status=open');
  const v3Alert = v3Open.items.find(item => item.deptCode === 'CARD') || v3Open.items[0];
  assert.ok(v3Alert, 'applying a red result must raise an alert');
  await v3request(`/alerts/${v3Alert.id}/ack`, { method: 'POST', body: { note: '集成检查确认' } });
  await v3request(`/alerts/${v3Alert.id}/resolve`, { method: 'POST', body: { note: '跳过分派' }, expected: 409 });
  await v3request(`/alerts/${v3Alert.id}/assign`, { method: 'POST', body: { assignee: '科室质控员', dueDate: '2026-12-31' } });
  await v3request(`/alerts/${v3Alert.id}/resolve`, { method: 'POST', body: { note: '集成检查关闭' } });
  passed('v3 alert state machine rejects invalid transitions and records transitions');

  const v3Improvement = await v3request('/improvements/from-alert', {
    method: 'POST',
    body: { alertId: v3Alert.id, rootCause: '围手术期评估不足', action: '补充术前讨论', owner: '心血管内科', dueDate: '2026-12-31' },
  });
  await v3request(`/improvements/${v3Improvement.id}/review`, {
    method: 'POST',
    body: { conclusion: 'whatever', note: '乱填' },
    expected: 400,
  });
  await v3request(`/improvements/${v3Improvement.id}/review`, {
    method: 'POST',
    body: { conclusion: 'improved', note: '没有证据' },
    expected: 400,
  });
  const v3Proof = new FormData();
  v3Proof.append('ownerType', 'improvement');
  v3Proof.append('ownerId', v3Improvement.id);
  v3Proof.append('title', 'v3 整改佐证');
  v3Proof.append('materialType', '检查记录');
  v3Proof.append('file', new Blob(['v3 improvement proof'], { type: 'text/plain' }), 'v3-proof.txt');
  const v3Evidence = await request('/v2/evidence', { method: 'POST', body: v3Proof });
  const v3Closed = await v3request(`/improvements/${v3Improvement.id}/review`, {
    method: 'POST',
    body: { conclusion: 'partially_improved', note: '已落实术前讨论', evidenceItemId: v3Evidence.item.id },
  });
  assert.equal(v3Closed.status, 'closed');
  assert.equal(v3Closed.evidenceItemId, v3Evidence.item.id);
  await v3request(`/improvements/${v3Improvement.id}/review`, {
    method: 'POST',
    body: { conclusion: 'improved', note: '重复', evidenceItemId: v3Evidence.item.id },
    expected: 409,
  });
  passed('v3 PDCA improvement requires evidence and a valid review conclusion');

  const v3FromResult = await v3request('/improvements', {
    method: 'POST',
    body: { sourceType: 'result', sourceId: v3I11.id, rootCause: '口径不一致', action: '统一口径', owner: '病案室', dueDate: '2026-12-31' },
  });
  assert.equal(v3FromResult.sourceType, 'result');
  assert.ok(v3FromResult.baselineValue !== null);
  const v3Chapter = v3Results.items[0].chapterCode;
  assert.ok(v3Chapter, '结果应带章节编码');
  const v3Filtered = await v3request(`/metric-results?chapterCode=${encodeURIComponent(v3Chapter)}`);
  assert.ok(v3Filtered.items.length > 0);
  assert.ok(v3Filtered.items.every(item => item.chapterCode === v3Chapter));
  passed('v3 improvement from result and chapter filter verified');

  const v3Report = await v3request('/reports', {
    method: 'POST',
    body: { versionId: v3Version, periodFrom: '2026-09', periodTo: '2026-10' },
  });
  assert.ok(v3Report.metricCount > 0);
  assert.ok(v3Report.batchIds.length > 0);
  const v3DocResponse = await fetch(base + `/api/v3/reports/${v3Report.id}/document`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.equal(v3DocResponse.status, 200);
  const v3Doc = Buffer.from(await v3DocResponse.arrayBuffer());
  assert.equal(v3Doc.subarray(0, 2).toString(), 'PK');
  assert.ok(v3Doc.includes(Buffer.from('word/document.xml')));
  const v3DocAgain = await fetch(base + `/api/v3/reports/${v3Report.id}/document`, { headers: { 'X-Demo-Role': 'quality' } });
  assert.ok(Buffer.from(await v3DocAgain.arrayBuffer()).equals(v3Doc), 'v3 报告必须字节稳定');
  passed('v3 report snapshot is stable and lists referenced batches');

  console.log(`Integration checks passed: ${checks}. DOCX snapshot files: ${output}`);
} finally {
  await request('/demo/reset', { method: 'POST', body: { confirm: 'RESET_DEMO' } });
}
