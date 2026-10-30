import { describe, expect, it } from "vitest";
import {
  allowedTaskActions,
  conclusionLabel,
  gradeLabel,
  parseDataRows,
  summarizeVersions,
} from "./domain";

describe("v0.2 批次输入解析", () => {
  it("解析指标编码、分子、分母与值四列", () => {
    const rows = parseDataRows("i01,960,2000,0.48");
    expect(rows).toEqual([
      { indicatorCode: "i01", numerator: 960, denominator: 2000, value: 0.48 },
    ]);
  });

  it("忽略空行与注释行", () => {
    const rows = parseDataRows("# 说明\n\ni02,1140,2000,57\n\n");
    expect(rows).toHaveLength(1);
    expect(rows[0]?.indicatorCode).toBe("i02");
  });

  it("非比例指标只给值时保留空分子分母", () => {
    const rows = parseDataRows("i14,,,8.1");
    expect(rows[0]).toEqual({ indicatorCode: "i14", value: 8.1 });
  });

  it("非数字单元格视为未提供，交给后端质量规则报错", () => {
    const rows = parseDataRows("i01,abc,2000,xyz");
    expect(rows[0]?.numerator).toBeUndefined();
    expect(rows[0]?.value).toBeUndefined();
    expect(rows[0]?.denominator).toBe(2000);
  });

  it("缺少指标编码的行被丢弃", () => {
    expect(parseDataRows(",100,200,50")).toHaveLength(0);
  });
});

describe("v0.2 任务动作与结论展示", () => {
  it("评审办可受理或退回已提交任务", () => {
    expect(allowedTaskActions("submitted", false)).toEqual([
      "accepted",
      "returned",
    ]);
  });

  it("科室负责人不能受理或退回，只能推进与提交", () => {
    expect(allowedTaskActions("submitted", true)).toEqual([]);
    expect(allowedTaskActions("todo", true)).toEqual(["in_progress"]);
    expect(allowedTaskActions("in_progress", true)).toEqual(["submitted"]);
  });

  it("终态任务没有可执行动作", () => {
    expect(allowedTaskActions("accepted", false)).toEqual([]);
    expect(allowedTaskActions("cancelled", false)).toEqual([]);
  });

  it("结论文案区分不纳入与不符合", () => {
    expect(conclusionLabel({ conform: null, reasonCode: "not_applicable" })).toBe(
      "不纳入 / not_applicable",
    );
    expect(conclusionLabel({ conform: false, reasonCode: "median_fail" })).toBe(
      "不符合 / median_fail",
    );
    expect(conclusionLabel({ conform: true, reasonCode: "pass" })).toBe(
      "符合 / pass",
    );
  });

  it("等次文案为空时显示未形成等次", () => {
    expect(gradeLabel(null)).toBe("未形成等次");
    expect(gradeLabel("jia")).toBe("甲等（试算）");
    expect(gradeLabel("unknown")).toBe("unknown");
  });

  it("材料版本摘要给出总数与最新版本号", () => {
    expect(summarizeVersions([])).toEqual({ count: 0, latest: null });
    expect(
      summarizeVersions([{ versionNo: 1 }, { versionNo: 3 }, { versionNo: 2 }]),
    ).toEqual({ count: 3, latest: 3 });
  });
});
