package cn.demo.review.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化的指标定义。v0.1 的六个平行数组已废弃：指标名称、章节、单位、方向、目标、演示当前值
 * 全部来自标准包 JSON，新增指标只需要在包里加一条，不再需要多处同步。
 */
public class IndicatorDefinition {
    public String code;
    public String name;
    public String clauseCode;
    public String chapterCode;
    public String dimension;
    public String unit;
    public String direction;
    public String scoreType;
    public String annualAgg;
    public String collectMode;
    public double target;
    public double warn;
    public String numeratorRule;
    public String denominatorRule;
    public String includeRule;
    public String excludeRule;
    /** 仅合成数据适配器使用，核心计分不读取。 */
    public Double demoCurrent;
    /** 合成分子分母基数，仅演示适配器使用。 */
    public Integer demoDenominator;
    /** 合成年度值，缺省时由 demoCurrent 按年度系数推导。 */
    public List<Double> demoAnnual = new ArrayList<>();

    public boolean isPercent() { return "%".equals(unit); }

    public boolean isLowBetter() { return "low".equals(direction); }
}
