import { describe, expect, it } from "vitest";
import {
  auditGate,
  filterIndicators,
  formatValue,
  improvementNext,
  label,
  scoreReasonLabel,
} from "./domain";

describe("指标筛选与评审显示边界", () => {
  const items = [
    {
      id: "a",
      code: "A01",
      name: "手术死亡率",
      owner: "医务科",
      chapter: "质量安全",
      light: "red",
      collectMode: "auto",
    },
    {
      id: "b",
      code: "B02",
      name: "护理人员配比",
      owner: "护理部",
      chapter: "资源配置",
      light: "green",
      collectMode: "manual",
    },
    {
      id: "c",
      code: "A03",
      name: "手术并发症",
      owner: "医务科",
      chapter: "质量安全",
      light: "yellow",
      collectMode: "auto",
    },
  ];
  it("同时满足章节、灯色和模糊搜索，空结果不回退全部", () => {
    expect(
      filterIndicators(items, {
        keyword: "手术",
        chapter: "质量安全",
        light: "red",
        mode: "ALL",
      }).map((x) => x.id),
    ).toEqual(["a"]);
    expect(
      filterIndicators(items, {
        keyword: "不存在",
        chapter: "ALL",
        light: "ALL",
        mode: "ALL",
      }),
    ).toEqual([]);
  });
  it("支持去空白编码搜索和采集方式过滤", () => {
    expect(
      filterIndicators(items, {
        keyword: " b02 ",
        chapter: "ALL",
        light: "ALL",
        mode: "manual",
      }).map((x) => x.id),
    ).toEqual(["b"]);
  });
  it("百分数使用原始百分数值，空值和零分别显示", () => {
    expect(formatValue(0.35, "%")).toBe("0.35%");
    expect(formatValue(null, "%")).toBe("—");
    expect(formatValue(0, "人")).toBe("0人");
  });
  it("指标定义把年度汇总代码显示为可理解的中文口径", () => {
    expect(label("month_avg")).toBe("月度值算术平均");
    expect(label("year_end_snapshot")).toBe("年末快照");
    expect(label("drg_formula")).toBe("年度DRG公式");
  });
  it.each([
    ["pass", "中位数与末年达标"],
    ["median", "取中位数"],
    ["median_fail", "中位数未达标"],
    ["worst_year", "趋势变差取最差年"],
    ["last_year_fail", "最后一年未达标"],
    ["not_applicable", "不适用"],
    ["future_reason", "计算说明待补充"],
  ])("试算原因 %s 显示为中文说明", (code, expected) => {
    expect(scoreReasonLabel(code)).toBe(expected);
  });
  it("条款错误率恰为10%不延期，超过10%延期", () => {
    expect(auditGate(0.1)).toBe(false);
    expect(auditGate(0.1001)).toBe(true);
  });
  it("改进只展示相邻合法状态，关闭状态只允许单独重开", () => {
    expect(improvementNext("opened")).toBe("assigned");
    expect(improvementNext("doing")).toBe("evidence_ready");
    expect(improvementNext("verified")).toBe("closed");
    expect(improvementNext("closed")).toBeNull();
  });
});
