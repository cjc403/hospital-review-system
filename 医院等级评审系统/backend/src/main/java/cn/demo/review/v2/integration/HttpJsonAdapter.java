package cn.demo.review.v2.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP/JSON 适配器：向配置的只读接口取数，失败重试一次后抛出可定位错误。
 * 只映射白名单字段，上游返回的患者级字段一律丢弃，不进入领域模型。
 */
public class HttpJsonAdapter implements ExternalSourceAdapter {
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient client;

    public HttpJsonAdapter(String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override public String code() { return "http-json"; }
    @Override public String kind() { return "http"; }
    @Override public String description() { return "从只读 HTTP 接口按周期取数：" + baseUrl; }

    @Override
    public List<SourceRow> fetch(String period) {
        String separator = baseUrl.contains("?") ? "&" : "?";
        URI uri = URI.create(baseUrl + separator + "period="
                + URLEncoder.encode(period, StandardCharsets.UTF_8));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) return parse(response.body());
                last = new AdapterException("上游返回 " + response.statusCode() + "（第 " + attempt + " 次尝试）");
            } catch (IOException e) {
                last = new AdapterException("上游不可达（第 " + attempt + " 次尝试）", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AdapterException("取数被中断", e);
            }
        }
        throw last == null ? new AdapterException("取数失败") : last;
    }

    private List<SourceRow> parse(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new AdapterException("上游响应不是合法 JSON", e);
        }
        JsonNode rows = root.path("rows");
        if (!rows.isArray()) throw new AdapterException("上游响应缺少 rows 数组");
        List<SourceRow> result = new ArrayList<>();
        for (JsonNode node : rows) {
            String code = node.path("indicatorCode").asText("");
            if (code.isBlank()) throw new AdapterException("上游第 " + (result.size() + 1) + " 行缺少指标编码");
            result.add(new SourceRow(code,
                    number(node, "numerator"), number(node, "denominator"),
                    number(node, "value"), node.path("unit").asText(null)));
        }
        return result;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new AdapterException("上游字段 " + field + " 不是数值");
        return value.asDouble();
    }
}
