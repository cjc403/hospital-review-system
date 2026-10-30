package cn.demo.review.v2.integration;

import java.util.List;

/**
 * 外部数据源适配器契约。真实接入 HIS/EMR/LIS/PACS 时新增实现，不改核心业务。
 * 适配器只产出指标级事实行，禁止返回患者标识；下游仍须经过 /batches 质量门。
 */
public interface ExternalSourceAdapter {
    String code();

    String kind();

    String description();

    List<SourceRow> fetch(String period);

    /** 指标级事实行：不含患者、住院号、姓名等任何个体标识。 */
    record SourceRow(String indicatorCode, Double numerator, Double denominator, Double value, String unit) {}

    class AdapterException extends RuntimeException {
        public AdapterException(String message) { super(message); }
        public AdapterException(String message, Throwable cause) { super(message, cause); }
    }
}
