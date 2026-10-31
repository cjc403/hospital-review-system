import { describe, expect, it } from "vitest";
import {
  alertActions,
  alertTypeLabels,
  formatValue,
  formulaLabels,
  lightBadge,
  lineagePath,
  needsAttention,
} from "./domain";
import type { V3Alert, V3Result } from "./types";

function result(partial: Partial<V3Result>): V3Result {
  return {
    id: "res-1",
    metricCode: "i01",
    metricName: "手术患者院内死亡率",
    unit: "%",
    direction: "low",
    period: "2026-11",
    deptCode: "CARD",
    value: 0.55,
    numerator: 11,
    denominator: 2000,
    light: "red",
    target: 0.3,
    warning: 0.4,
    reasonCode: "pass",
    qualityStatus: "validated",
    batchId: "ing-1",
    definitionId: "def-1",
    definitionVersion: 2,
    formulaType: "ratio",
    computedAt: "2026-11-30T00:00:00+08:00",
    ...partial,
  };
}

describe("v3 展示逻辑", () => {
  it("灯号映射到既有徽章样式与文案", () => {
    expect(lightBadge.red).toBe("badge-red");
    expect(lightBadge.na).toBe("badge-na");
  });

  it("公式类型有中文解释，未知类型回退原值", () => {
    expect(formulaLabels.drg_formula).toContain("DRG");
    expect(formulaLabels.unknown_type).toBeUndefined();
  });

  it("数值格式化保留空值为破折号", () => {
    expect(formatValue(null, "%")).toBe("—");
    expect(formatValue(0.55, "%")).toBe("0.55%");
    expect(formatValue(870, "张")).toBe("870张");
  });

  it("红黄灯与缺失值需要关注", () => {
    expect(needsAttention(result({ light: "red" }))).toBe(true);
    expect(needsAttention(result({ light: "yellow" }))).toBe(true);
    expect(needsAttention(result({ light: "green" }))).toBe(false);
    expect(needsAttention(result({ value: null, light: "na" }))).toBe(true);
  });

  it("预警动作与后端状态机一致", () => {
    expect(alertActions("open")).toEqual(["ack", "ignore"]);
    expect(alertActions("acknowledged")).toEqual(["assign", "ignore"]);
    expect(alertActions("assigned")).toEqual(["resolve", "ignore"]);
    expect(alertActions("resolved" as V3Alert["status"])).toEqual([]);
  });

  it("告警类型有中文文案", () => {
    expect(alertTypeLabels.below_target).toBe("低于目标");
    expect(alertTypeLabels.over_warning).toBe("超过预警线");
  });

  it("血缘路径按来源到结果排序", () => {
    const path = lineagePath([
      { type: "result", label: "i11 结果" },
      { type: "source", label: "CSV-IMPORT" },
      { type: "batch", label: "批次 A" },
      { type: "definition", label: "i11 定义 v1" },
    ]);
    expect(path).toBe("CSV-IMPORT → 批次 A → i11 定义 v1 → i11 结果");
  });
});
