import type { V2TaskStatus } from "./types";

export interface DataRowInput {
  indicatorCode: string;
  numerator?: number;
  denominator?: number;
  value?: number;
}

/**
 * 解析“指标编码,分子,分母,值”文本为批次行。
 * 空行与 # 注释忽略；非数字单元格视为未提供，由后端质量规则给出可定位错误。
 */
export function parseDataRows(text: string): DataRowInput[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("#"))
    .map((line) => {
      const [code, numerator, denominator, value] = line
        .split(",")
        .map((cell) => cell.trim());
      const row: DataRowInput = { indicatorCode: code || "" };
      const num = toNumber(numerator);
      const den = toNumber(denominator);
      const val = toNumber(value);
      if (num !== undefined) row.numerator = num;
      if (den !== undefined) row.denominator = den;
      if (val !== undefined) row.value = val;
      return row;
    })
    .filter((row) => row.indicatorCode.length > 0);
}

function toNumber(raw: string | undefined): number | undefined {
  if (raw === undefined || raw === "") return undefined;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : undefined;
}

const NEXT: Record<V2TaskStatus, V2TaskStatus[]> = {
  todo: ["in_progress", "cancelled"],
  in_progress: ["submitted", "cancelled"],
  submitted: ["accepted", "returned"],
  returned: ["in_progress"],
  accepted: [],
  cancelled: [],
};

/**
 * 任务可执行动作。科室负责人只能推进本科室任务，不能受理或退回；
 * 这里与后端状态机保持一致，但只是体验层，真正的授权仍由服务端判定。
 */
export function allowedTaskActions(
  status: V2TaskStatus,
  deptScoped: boolean,
): V2TaskStatus[] {
  const actions = NEXT[status] || [];
  return deptScoped
    ? actions.filter((item) => item === "in_progress" || item === "submitted")
    : actions;
}

export function conclusionLabel(item: {
  conform: boolean | null;
  reasonCode: string;
}): string {
  if (item.conform === null) return `不纳入 / ${item.reasonCode}`;
  return `${item.conform ? "符合" : "不符合"} / ${item.reasonCode}`;
}

const GRADES: Record<string, string> = {
  jia: "甲等（试算）",
  yi: "乙等（试算）",
  bing: "丙等（试算）",
  deferred: "延期（试算）",
};

export function gradeLabel(grade: string | null): string {
  if (!grade) return "未形成等次";
  return GRADES[grade] || grade;
}

/** 材料版本摘要：旧版本只读保留，界面显示总版本数与最新版本号。 */
export function summarizeVersions(
  versions: { versionNo: number }[],
): { count: number; latest: number | null } {
  if (!versions.length) return { count: 0, latest: null };
  return {
    count: versions.length,
    latest: Math.max(...versions.map((item) => item.versionNo)),
  };
}
