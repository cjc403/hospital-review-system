package cn.demo.review.v2.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件型适配器：按周期读取 `{dir}/{period}.csv`。
 * 表头必须含 indicatorCode，可选 numerator/denominator/value/unit；
 * 其余列一律忽略——这是脱敏白名单，患者级字段不会被带进来。
 */
public class CsvFileAdapter implements ExternalSourceAdapter {
    private final Path directory;

    public CsvFileAdapter(Path directory) { this.directory = directory; }

    @Override public String code() { return "csv-file"; }
    @Override public String kind() { return "csv"; }
    @Override public String description() { return "按周期读取本地 CSV 文件（适配器目录：" + directory + "）"; }

    @Override
    public List<SourceRow> fetch(String period) {
        Path file = directory.resolve(period + ".csv");
        if (!Files.isRegularFile(file)) {
            throw new AdapterException("找不到周期文件：" + period + ".csv");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AdapterException("读取失败：" + file.getFileName(), e);
        }
        List<String> meaningful = lines.stream().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        if (meaningful.isEmpty()) throw new AdapterException("文件为空：" + file.getFileName());
        Map<String, Integer> columns = header(meaningful.get(0));
        if (!columns.containsKey("indicatorcode")) {
            throw new AdapterException("表头缺少 indicatorCode 列");
        }
        List<SourceRow> rows = new ArrayList<>();
        for (int i = 1; i < meaningful.size(); i++) {
            String[] cells = meaningful.get(i).split(",", -1);
            String code = cell(cells, columns.get("indicatorcode"));
            if (code == null || code.isBlank()) throw new AdapterException("第 " + (i + 1) + " 行缺少指标编码");
            rows.add(new SourceRow(code,
                    number(cells, columns.get("numerator"), i + 1, "numerator"),
                    number(cells, columns.get("denominator"), i + 1, "denominator"),
                    number(cells, columns.get("value"), i + 1, "value"),
                    cell(cells, columns.get("unit"))));
        }
        return rows;
    }

    private static Map<String, Integer> header(String line) {
        Map<String, Integer> columns = new HashMap<>();
        String[] cells = line.split(",", -1);
        for (int i = 0; i < cells.length; i++) {
            columns.put(cells[i].trim().toLowerCase(), i);
        }
        return columns;
    }

    private static String cell(String[] cells, Integer index) {
        if (index == null || index >= cells.length) return null;
        String value = cells[index].trim();
        return value.isEmpty() ? null : value;
    }

    private static Double number(String[] cells, Integer index, int lineNo, String field) {
        String raw = cell(cells, index);
        if (raw == null) return null;
        try {
            double parsed = Double.parseDouble(raw);
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new AdapterException("第 " + lineNo + " 行 " + field + " 不是有限数值：" + raw);
        }
    }
}
