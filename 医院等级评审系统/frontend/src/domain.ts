interface Filterable {
  name: string;
  code: string;
  owner: string;
  chapter: string;
  light: string;
  collectMode: string;
}
export interface IndicatorFilters {
  keyword: string;
  chapter: string;
  light: string;
  mode: string;
}
export function filterIndicators<T extends Filterable>(
  items: T[],
  filters: IndicatorFilters,
): T[] {
  const keyword = filters.keyword.trim().toLocaleLowerCase();
  return items.filter(
    (item) =>
      (!keyword ||
        `${item.code} ${item.name} ${item.owner}`
          .toLocaleLowerCase()
          .includes(keyword)) &&
      (filters.chapter === "ALL" || item.chapter === filters.chapter) &&
      (filters.light === "ALL" || item.light === filters.light) &&
      (filters.mode === "ALL" || item.collectMode === filters.mode),
  );
}
export function formatValue(
  value: number | null | undefined,
  unit = "",
): string {
  return value == null || !Number.isFinite(value)
    ? "—"
    : `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 4 }).format(value)}${unit}`;
}
export function improvementNext(status: string): string | null {
  const states = [
    "opened",
    "assigned",
    "doing",
    "evidence_ready",
    "verified",
    "closed",
  ];
  const index = states.indexOf(status);
  return index >= 0 && index < states.length - 1 ? states[index + 1]! : null;
}
export function auditGate(rate: number): boolean {
  return rate > 0.1;
}
export function formatDate(value: string): string {
  return value ? value.replace("T", " ").slice(0, 16) : "—";
}
export const statusLabels: Record<string, string> = {
  month_avg: "月度值算术平均",
  year_end_snapshot: "年末快照",
  drg_formula: "年度DRG公式",
  green: "达标",
  yellow: "关注",
  red: "预警",
  na: "待采集",
  auto: "自动采集",
  manual: "人工补录",
  submitted: "待审核",
  approved: "已通过",
  rejected: "已退回",
  accepted: "已复核",
  draft: "草稿",
  compliant: "符合",
  non_compliant: "不符合",
  unknown: "待确认",
  pending: "待说明",
  explained: "已说明",
  success: "成功",
  failed: "失败",
  opened: "待指派",
  assigned: "已指派",
  doing: "改进中",
  evidence_ready: "待验证",
  verified: "已验证",
  closed: "已关闭",
  active: "已生效",
  registered: "待确认",
  confirmed: "已确认",
  blocked: "规则阻断",
  jia: "甲等试算",
  yi: "乙等试算",
  bing: "丙等试算",
  deferred: "延期试算",
  red_light: "红灯预警",
  worst_year: "最差年度",
  interval_trend: "区间趋势",
  all_or_nothing: "全有全无",
};
export function label(value: string | null): string {
  return value ? statusLabels[value] || value : "未形成等次";
}

export function scoreReasonLabel(code: string): string {
  const reasons: Record<string, string> = {
    pass: "中位数与末年达标",
    median: "取中位数",
    median_fail: "中位数未达标",
    worst_year: "趋势变差取最差年",
    last_year_fail: "最后一年未达标",
    not_applicable: "不适用",
    conform: "符合",
    below_target: "未达到演示目标",
    no_data: "无有效数据",
    all_or_nothing: "中位数与末年判定",
    interval_trend: "区间趋势判定",
    improving: "改善趋势",
    deteriorating: "变差趋势",
    stable: "稳定趋势",
    insufficient: "数据不足",
  };
  return reasons[code] || "计算说明待补充";
}
