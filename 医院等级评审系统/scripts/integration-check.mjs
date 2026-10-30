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

  console.log(`Integration checks passed: ${checks}. DOCX snapshot files: ${output}`);
} finally {
  await request('/demo/reset', { method: 'POST', body: { confirm: 'RESET_DEMO' } });
}
