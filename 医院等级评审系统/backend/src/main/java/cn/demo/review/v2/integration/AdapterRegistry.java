package cn.demo.review.v2.integration;

import cn.demo.review.v2.V2Exception;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册表：只有配置了来源的适配器才出现在列表里，未配置就是空列表，不假装能取数。
 * 取到的事实只作为预览返回，写入仍必须经过 /batches 的质量门。
 */
@Component
public class AdapterRegistry {
    private final Map<String, ExternalSourceAdapter> adapters = new LinkedHashMap<>();

    public AdapterRegistry(@Value("${review.integration.csv-dir:}") String csvDir,
                           @Value("${review.integration.http-url:}") String httpUrl,
                           ObjectMapper json) {
        if (csvDir != null && !csvDir.isBlank()) {
            adapters.put("csv-file", new CsvFileAdapter(Path.of(csvDir)));
        }
        if (httpUrl != null && !httpUrl.isBlank()) {
            adapters.put("http-json", new HttpJsonAdapter(httpUrl, json));
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExternalSourceAdapter adapter : adapters.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", adapter.code());
            item.put("kind", adapter.kind());
            item.put("description", adapter.description());
            item.put("configured", true);
            items.add(item);
        }
        return items;
    }

    public List<ExternalSourceAdapter.SourceRow> fetch(String code, String period) {
        ExternalSourceAdapter adapter = adapters.get(code);
        if (adapter == null) {
            throw V2Exception.notFound("适配器未配置：" + code + "（设置 review.integration.csv-dir 或 review.integration.http-url）");
        }
        try {
            return adapter.fetch(period);
        } catch (ExternalSourceAdapter.AdapterException e) {
            throw V2Exception.conflict("取数失败：" + e.getMessage());
        }
    }
}
