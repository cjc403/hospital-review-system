import type { V3Alert, V3Result } from "./types";

/** 灯号文案与样式，和 v2 展示保持一致。 */
export const lightLabels: Record<string, string> = {
  green: "达标",
  yellow: "预警",
  red: "超标",
  na: "无数据",
};

export const lightBadge: Record<string, string> = {
  green: "badge-green",
  yellow: "badge-yellow",
  red: "badge-red",
  na: "badge-na",
};

export const formulaLabels: Record<string, string> = {
  ratio: "比例（分子/分母）",
  weighted_ratio: "加权比例（Σ分子/Σ分母）",
  month_avg: "月度平均",
  year_end_snapshot: "年末快照",
  drg_formula: "DRG 加权",
  trend: "趋势",
};

export function formatValue(value: number | null, unit: string): string {
  if (value === null || !Number.isFinite(value)) return "—";
  const text = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 4 }).format(value);
  return `${text}${unit || ""}`;
}

/** 预警下一步可执行动作：与后端状态机一致，前端只控制展示。 */
export function alertActions(status: V3Alert["status"]): string[] {
  switch (status) {
    case "open":
      return ["ack", "ignore"];
    case "acknowledged":
      return ["assign", "ignore"];
    case "assigned":
      return ["resolve", "ignore"];
    default:
      return [];
  }
}

export const alertActionLabels: Record<string, string> = {
  ack: "确认",
  assign: "分派",
  resolve: "解决",
  ignore: "忽略",
};

export const alertTypeLabels: Record<string, string> = {
  below_target: "低于目标",
  over_warning: "超过预警线",
  worsening: "连续恶化",
  jump: "异常跳变",
  missing_data: "数据缺失",
  quality_open: "质量问题未处理",
};

/** 结果是否需要在列表里高亮（红/黄）。 */
export function needsAttention(result: V3Result): boolean {
  return result.light === "red" || result.light === "yellow" || result.value === null;
}

/** 血缘链路的可读路径，例如 数据源 → 批次 → 结果 → 预警。 */
export function lineagePath(nodes: { type: string; label: string }[]): string {
  const order = ["source", "batch", "facts", "definition", "result", "alert", "improvement", "report"];
  return nodes
    .slice()
    .sort((a, b) => order.indexOf(a.type) - order.indexOf(b.type))
    .map((node) => node.label)
    .join(" → ");
}
