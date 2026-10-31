/** v3 契约类型，对应 docs/v3-api-contract.md。 */

export interface V3Definition {
  id: string;
  code: string;
  name: string;
  unit: string;
  direction: "high" | "low";
  ownerScope: string;
  formulaType: string;
  aggregation: string;
  target: number | null;
  warning: number | null;
  versionNo: number;
  status: string;
  effectiveFrom: string | null;
}

export interface V3Mapping {
  sourceCode: string;
  fields: Record<string, string>;
  codeMap: Record<string, Record<string, string>>;
  version: number;
  updatedAt: string;
}

export interface V3Batch {
  id: string;
  sourceCode: string;
  versionId?: string;
  period: string;
  status: "validated" | "rejected" | "applied";
  recordCount: number;
  checksum?: string;
  rejectReason?: string;
  replayOf?: string | null;
  createdAt: string;
}

export interface V3Issue {
  rowNo: number;
  rule: string;
  field: string;
  message: string;
  severity: "error" | "warning";
}

export interface V3BatchDetail {
  batch: V3Batch;
  issues: V3Issue[];
  facts: {
    metricCode: string;
    deptCode: string;
    period: string;
    numerator: number | null;
    denominator: number | null;
    value: number | null;
    unit: string;
  }[];
  trace: { metricCode: string | null; formulaType: string | null; ruleVersion: string; detail: string }[];
}

export interface V3Result {
  id: string;
  metricCode: string;
  metricName: string;
  unit: string;
  direction: "high" | "low";
  period: string;
  deptCode: string;
  value: number | null;
  numerator: number | null;
  denominator: number | null;
  light: "green" | "yellow" | "red" | "na";
  target: number | null;
  warning: number | null;
  reasonCode: string;
  qualityStatus: string;
  batchId: string | null;
  definitionId: string | null;
  definitionVersion: number;
  formulaType: string;
  computedAt: string;
}

export interface V3Drilldown {
  result: V3Result;
  departments: {
    deptCode: string;
    value: number | null;
    numerator: number | null;
    denominator: number | null;
    light: string;
  }[];
  members: { masked: boolean; items: unknown[]; note?: string };
}

export interface V3Lineage {
  nodes: { type: string; id: string; label: string }[];
  edges: { from: string; to: string; relation: string }[];
}

export interface V3Alert {
  id: string;
  metricCode: string;
  metricName: string;
  deptCode: string;
  period: string;
  type: string;
  severity: "high" | "medium" | "low";
  status: "open" | "acknowledged" | "assigned" | "resolved" | "ignored";
  detail: string;
  resultId: string;
  assignee: string | null;
  dueDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface V3Improvement {
  id: string;
  alertId: string;
  metricCode: string;
  metricName: string;
  rootCause: string;
  action: string;
  owner: string;
  dueDate: string;
  status: string;
  reviewConclusion: string | null;
  reviewNote: string | null;
  evidenceItemId: string | null;
  closedAt: string | null;
  createdAt: string;
}

export interface V3Report {
  id: string;
  versionId: string;
  periodFrom: string;
  periodTo: string;
  metricCount: number;
  alertCount: number;
  improvementCount: number;
  batchIds: string[];
  definitions: string[];
  createdAt: string;
}
