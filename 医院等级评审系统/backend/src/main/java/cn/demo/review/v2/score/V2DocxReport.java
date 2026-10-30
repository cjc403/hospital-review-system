package cn.demo.review.v2.score;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 周期试算 DOCX 报告。只读评分快照与其引用的批次/材料版本，
 * 不重新查询当前指标或材料，保证旧报告字节稳定。
 */
final class V2DocxReport {
    private V2DocxReport() {}

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> report, Map<String, Object> snapshot) throws IOException {
        Map<String, Object> run = (Map<String, Object>) report.get("run");
        List<Map<String, Object>> items = (List<Map<String, Object>>) report.get("items");
        StringBuilder body = new StringBuilder();
        paragraph(body, "医院等级评审周期试算报告", true);
        paragraph(body, "演示口径 / 合成数据 / 不作正式评审结论", true);
        paragraph(body, "模拟医院：江城市第一人民医院（虚构）", false);
        paragraph(body, "标准包：" + run.get("packageTitle") + "（版本 " + run.get("versionNo") + "）", false);
        paragraph(body, "评审周期：" + run.get("cycleStart") + "—" + run.get("cycleEnd"), false);
        paragraph(body, "试算编号：" + run.get("id") + "；生成时间：" + run.get("createdAt"), false);
        paragraph(body, "符合指标：" + run.get("conformCount") + " / 纳入 " + run.get("includedCount")
                + "；符合比例：" + percent(run.get("ratio")) + "；模拟等次：" + grade((String) run.get("grade")), true);
        if (run.get("deferredReason") != null && !String.valueOf(run.get("deferredReason")).isBlank()) {
            paragraph(body, "延期/不形成等次原因：" + run.get("deferredReason"), false);
        }

        paragraph(body, "一、指标年度合并结果", true);
        StringBuilder table = new StringBuilder(
                "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/>"
                        + "<w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/>"
                        + "<w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>");
        row(table, "指标", "年度值", "合并值", "结论 / 原因码");
        for (Map<String, Object> item : items) {
            Object conform = item.get("conform");
            String conclusion = conform == null ? "不纳入 / " + item.get("reasonCode")
                    : (Boolean.TRUE.equals(conform) ? "符合 / " : "不符合 / ") + item.get("reasonCode");
            row(table, String.valueOf(item.get("name")), String.valueOf(item.get("yearValues")),
                    value(item.get("mergedValue")) + String.valueOf(item.get("unit") == null ? "" : item.get("unit")),
                    conclusion);
        }
        table.append("</w:tbl>");
        body.append(table);

        paragraph(body, "二、数据来源批次", true);
        Object batchIds = snapshot.get("batchIds");
        paragraph(body, "本次试算引用的已应用批次：" + (batchIds == null ? "无" : batchIds.toString()), false);
        paragraph(body, "事实行数：" + snapshot.getOrDefault("factCount", 0)
                + "；标准口径：版本 " + run.get("versionNo") + "，校验和 " + run.getOrDefault("checksum", "见标准版本记录"), false);

        paragraph(body, "三、材料版本清单", true);
        Object evidence = snapshot.get("evidence");
        if (evidence instanceof List<?> list && !list.isEmpty()) {
            for (Object entry : list) {
                Map<String, Object> item = (Map<String, Object>) entry;
                paragraph(body, item.get("title") + "：版本 " + item.get("versionNo")
                        + "，SHA-256 " + item.get("sha256") + "，文件 " + item.get("fileName"), false);
            }
        } else {
            paragraph(body, "本次试算时没有已审核通过的材料版本。", false);
        }

        paragraph(body, "四、声明", true);
        paragraph(body, "全部数据为合成样本，未连接真实医院系统；指标口径为演示覆盖集，不声称覆盖 203 条日常监测指标。", false);
        paragraph(body, "本报告绑定生成时的评分快照：后续指标修改、材料更新或新批次导入不会改变本报告内容。", false);

        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\""
                + " w:bottom=\"1134\" w:left=\"850\"/></w:sectPr></w:body></w:document>";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                    + " Target=\"word/document.xml\"/></Relationships>");
            entry(zip, "word/document.xml", document);
            zip.finish();
            return out.toByteArray();
        }
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    private static void paragraph(StringBuilder body, String text, boolean heading) {
        body.append("<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/>");
        body.append(heading ? "<w:b/><w:sz w:val=\"26\"/>" : "<w:sz w:val=\"20\"/>");
        body.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>");
    }

    private static void row(StringBuilder table, String... cells) {
        table.append("<w:tr>");
        for (String cell : cells) {
            table.append("<w:tc><w:tcPr><w:tcW w:w=\"2500\" w:type=\"dxa\"/></w:tcPr>");
            paragraph(table, cell, false);
            table.append("</w:tc>");
        }
        table.append("</w:tr>");
    }

    private static String value(Object value) {
        if (value == null) return "—";
        if (value instanceof Number number) {
            return java.math.BigDecimal.valueOf(number.doubleValue())
                    .setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private static String percent(Object ratio) {
        if (!(ratio instanceof Number number)) return "—";
        return value(number.doubleValue() * 100) + "%";
    }

    private static String grade(String grade) {
        if (grade == null) return "未形成等次";
        return switch (grade) {
            case "jia" -> "甲等（试算）";
            case "yi" -> "乙等（试算）";
            case "bing" -> "丙等（试算）";
            case "deferred" -> "延期（试算）";
            default -> grade;
        };
    }
}
