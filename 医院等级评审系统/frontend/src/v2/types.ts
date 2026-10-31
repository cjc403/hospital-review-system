/** v0.2 契约类型，对应 docs/next-api-contract.md。 */

export interface V2Version {
  id: string;
  versionNo: number;
  status: "draft" | "published" | "retired";
  publishedOn: string | null;
  effectiveFrom: string | null;
  checksum: string | null;
  createdAt: string;
}

export interface V2Package {
  id: string;
  code: string;
  title: string;
  issuer: string;
  sourceLocator: string;
  versions: V2Version[];
}

export interface V2Chapter {
  code: string;
  title: string;
  sort: number;
}

export interface V2Clause {
  code: string;
  chapterCode: string;
  title: string;
  requirement: string;
  evidenceType: string;
  ownerScope: string;
  evaluationMethod: string;
  sourceLocator: string;
}

export interface V2IndicatorDef {
  code: string;
  name: string;
  clauseCode: string;
  chapterCode: string;
  dimension: string;
  unit: string;
  direction: "high" | "low";
  scoreType: "all_or_nothing" | "interval_trend";
  annualAgg: string;
  collectMode: "auto" | "manual";
  target: number | null;
  warn: number | null;
}

export interface V2PrereqDef {
  code: string;
  section: string;
  title: string;
  statement: string;
  systemGenerated: boolean;
}

export interface V2VersionDetail {
  version: {
    id: string;
    packageId: string;
    packageCode: string;
    versionNo: number;
    status: string;
    publishedOn: string | null;
    effectiveFrom: string | null;
    appliesTo: string | null;
    note: string | null;
    checksum: string | null;
    createdAt: string;
  };
  chapters: V2Chapter[];
  clauses: V2Clause[];
  indicators: V2IndicatorDef[];
  prerequisites: V2PrereqDef[];
  counts: {
    chapters: number;
    clauses: number;
    indicators: number;
    prerequisites: number;
  };
}

export type V2TaskStatus =
  | "todo"
  | "in_progress"
  | "submitted"
  | "returned"
  | "accepted"
  | "cancelled";

export interface V2Task {
  id: string;
  hospitalId: string;
  versionId: string;
  clauseCode: string;
  deptCode: string;
  title: string;
  assigneePost: string;
  priority: "high" | "normal" | "low";
  dueDate: string;
  status: V2TaskStatus;
  returnReason: string;
  overdue: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface V2TaskSummary {
  total: number;
  todo: number;
  in_progress: number;
  submitted: number;
  returned: number;
  accepted: number;
  cancelled: number;
  overdue: number;
}

export interface V2EvidenceItem {
  id: string;
  ownerType: string;
  ownerId: string;
  title: string;
  materialType: string;
  status: "pending" | "approved" | "rejected";
  validUntil: string | null;
  createdBy: string;
  createdAt: string;
  versionCount: number;
}

export interface V2EvidenceVersion {
  id: string;
  itemId?: string;
  versionNo: number;
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
  uploadedBy?: string;
  uploadedAt?: string;
}

export interface V2Batch {
  id: string;
  sourceCode: string;
  period: string;
  status: "validated" | "rejected" | "applied";
  recordCount: number;
  checksum: string;
  rejectReason: string;
  replayOf: string | null;
  createdAt: string;
  appliedAt: string | null;
}

export interface V2QualityIssue {
  rowNo: number;
  rule: string;
  field: string;
  message: string;
  severity: "error" | "warning";
}

export interface V2BatchDetail {
  batch: V2Batch;
  facts: {
    indicatorCode: string;
    period: string;
    numerator: number | null;
    denominator: number | null;
    excludedCount: number;
    value: number | null;
    unit: string;
  }[];
  issues: V2QualityIssue[];
  trace: {
    ruleVersion: string;
    engine: string;
    executedAt: string;
    durationMs: number;
    detail: string;
  }[];
}

export interface V2ScoreRunSummary {
  id: string;
  versionId: string;
  versionNo: number;
  cycleStart: number;
  cycleEnd: number;
  grade: string | null;
  conformCount: number;
  includedCount: number;
  ratio: number;
  deferredReason: string;
  createdAt: string;
}

export interface V2ScoreItem {
  indicatorCode: string;
  name: string;
  unit: string;
  yearValues: { year: number; value: number | null }[];
  mergedValue: number | null;
  conform: boolean | null;
  reasonCode: string;
}

export interface V2ScoreRunDetail {
  run: V2ScoreRunSummary & {
    packageCode: string;
    packageTitle: string;
    status: string;
    snapshot: string;
  };
  items: V2ScoreItem[];
}

export interface V2Department {
  code: string;
  name: string;
  hospitalName: string;
}
