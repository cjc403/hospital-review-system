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
  console.log(`Integration checks passed: ${checks}. DOCX snapshot files: ${output}`);
} finally {
  await request('/demo/reset', { method: 'POST', body: { confirm: 'RESET_DEMO' } });
}
