export type Role = "quality" | "leader" | "department" | "information";
export type Light = "green" | "yellow" | "red" | "na";
export interface Indicator {
  id: string;
  code: string;
  name: string;
  chapter: string;
  dimension: string;
  owner: string;
  unit: string;
  direction: "high" | "low";
  scoreType: "all_or_nothing" | "interval_trend";
  annualAgg: string;
  collectMode: "auto" | "manual";
  target: number;
  warn: number;
  value: number | null;
  autoValue: number | null;
  numerator: number | null;
  denominator: number | null;
  light: Light;
  source: "auto" | "manual";
  definitionVersion: number;
  numeratorText: string;
  denominatorText: string;
  includeText: string;
  excludeText: string;
  sourceTables: string[];
  batchId: string;
  excludedCount: number;
  trends: { period: string; value: number | null }[];
  annualValues: { year: number; value: number | null }[];
  departmentValues: {
    code: string;
    name: string;
    value: number | null;
    numerator: number | null;
    denominator: number | null;
    light: Light;
  }[];
}
export interface Batch {
  id: string;
  period: string;
  status: "success" | "failed";
  batchKind: "auto" | "manual";
  indicatorCount: number;
  createdAt: string;
  note: string;
}
export interface Manual {
  id: string;
  indicatorId: string;
  indicatorName: string;
  deptCode: string;
  period: string;
  value: number;
  note: string;
  status: "submitted" | "approved" | "rejected";
  submitter: string;
  reviewer: string;
  submittedAt: string;
}
export interface Reconcile {
  id: string;
  inpatientNo: string;
  dischargeDate: string;
  deptName: string;
  diffType: string;
  homeValue: string;
  hqmsValue: string;
  status: "pending" | "explained";
  note: string;
}
export interface AuditSample {
  id: string;
  period: string;
  sampleCount: number;
  errorCount: number;
  errorRate: number;
  createdAt: string;
  items: {
    indicatorId: string;
    name: string;
    homeValue: number | null;
    hqmsValue: number | null;
    deviation: number | null;
    result: string;
  }[];
}
export interface Prereq {
  id: string;
  code: string;
  section: string;
  title: string;
  compliance: "compliant" | "non_compliant" | "unknown";
  workflow: "draft" | "submitted" | "accepted" | "rejected";
  owner: string;
  statement: string;
  systemGenerated: boolean;
  evidenceCount: number;
}
export interface Improvement {
  id: string;
  indicatorId: string;
  indicatorName: string;
  origin: "red_light" | "worst_year";
  status: string;
  owner: string;
  dueDate: string;
  baseline: number;
  target: number;
  latestValue: number | null;
  actionText: string;
  closeMode: string;
  closeNote: string;
  evidenceCount: number;
}
export interface Policy {
  id: string;
  code: string;
  title: string;
  kind: string;
  status: string;
  publishedOn: string;
  effectiveOn: string;
  version: number;
  impacts: string[];
  note: string;
}
export interface ScoreRun {
  id: string;
  cycleStart: number;
  cycleEnd: number;
  yearCount: number;
  trial: boolean;
  conformCount: number;
  includedCount: number;
  ratio: number;
  grade: "jia" | "yi" | "bing" | "deferred" | null;
  createdAt: string;
  adopted: boolean;
  warnings: string[];
  items: {
    indicatorId: string;
    name: string;
    unit: string;
    yearValues: { year: number; value: number | null }[];
    mergedValue: number | null;
    conform: boolean | null;
    reasonCode: string;
  }[];
}
export interface Evidence {
  id: string;
  ownerType: string;
  ownerId: string;
  fileName: string;
  size: number;
  version: number;
  createdAt: string;
}
export interface AuditLog {
  id: string;
  actor: string;
  action: string;
  objectId: string;
  detail: string;
  createdAt: string;
}
export interface Workspace {
  meta: {
    hospitalName: string;
    standard: string;
    period: string;
    demo: boolean;
    dataAsOf: string;
    totalMonitoringClauses: number;
  };
  departments: { code: string; name: string; owner: string }[];
  indicators: Indicator[];
  batches: Batch[];
  manualValues: Manual[];
  reconciliations: Reconcile[];
  auditSamples: AuditSample[];
  prerequisites: Prereq[];
  improvements: Improvement[];
  policies: Policy[];
  scoreRuns: ScoreRun[];
  evidenceFiles: Evidence[];
  auditLogs: AuditLog[];
}
export interface Member {
  inpatientNo: string;
  dischargeDate: string;
  deptCode: string;
  deptName: string;
  sex: string;
  age: number;
  mainDiagnosis: string;
  memberRole: string;
}
export interface MemberPage {
  items: Member[];
  total: number;
  page: number;
  size: number;
}
