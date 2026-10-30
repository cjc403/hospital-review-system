package cn.demo.review;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.integration.AdapterRegistry;
import cn.demo.review.v2.integration.CsvFileAdapter;
import cn.demo.review.v2.integration.ExternalSourceAdapter;
import cn.demo.review.v2.integration.HttpJsonAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部数据适配器契约测试：只用本地 CSV 与本地模拟 HTTP 服务，不连接任何真实医院系统。
 * 同时验证脱敏白名单——上游返回的患者级字段不会进入适配器输出。
 */
class ExternalAdapterTest {

    @Test
    void csvAdapterMapsWhitelistedColumnsAndIgnoresPatientFields() throws Exception {
        Path dir = Files.createTempDirectory("adapter-csv-");
        Files.writeString(dir.resolve("2026-12.csv"), """
                indicatorCode,numerator,denominator,value,unit,patientName,inpatientNo
                i01,960,2000,0.48,%,张三,DEMO-1
                i14,,,8.1,天,李四,DEMO-2
                """, StandardCharsets.UTF_8);
        CsvFileAdapter adapter = new CsvFileAdapter(dir);
        List<ExternalSourceAdapter.SourceRow> rows = adapter.fetch("2026-12");
        assertEquals(2, rows.size());
        assertEquals("i01", rows.get(0).indicatorCode());
        assertEquals(960d, rows.get(0).numerator());
        assertEquals(0.48d, rows.get(0).value());
        assertNull(rows.get(1).numerator());
        assertEquals(8.1d, rows.get(1).value());
        // 记录类型只有指标级字段，患者列无处可放
        assertEquals(5, ExternalSourceAdapter.SourceRow.class.getRecordComponents().length);
    }

    @Test
    void csvAdapterReportsMissingFileAndBadNumber() throws Exception {
        Path dir = Files.createTempDirectory("adapter-csv-bad-");
        CsvFileAdapter adapter = new CsvFileAdapter(dir);
        assertThrows(ExternalSourceAdapter.AdapterException.class, () -> adapter.fetch("2026-01"));

        Files.writeString(dir.resolve("2026-02.csv"), "indicatorCode,value\ni01,not-a-number\n",
                StandardCharsets.UTF_8);
        ExternalSourceAdapter.AdapterException error = assertThrows(
                ExternalSourceAdapter.AdapterException.class, () -> adapter.fetch("2026-02"));
        assertTrue(error.getMessage().contains("第 2 行"));
    }

    @Test
    void httpAdapterReadsMockServerAndDropsPatientFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/facts", exchange -> {
            String body = "{\"rows\":[{\"indicatorCode\":\"i01\",\"numerator\":940,\"denominator\":2000,"
                    + "\"value\":0.47,\"unit\":\"%\",\"patientName\":\"王五\",\"inpatientNo\":\"DEMO-9\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpJsonAdapter adapter = new HttpJsonAdapter(
                    "http://127.0.0.1:" + port + "/facts", new ObjectMapper());
            List<ExternalSourceAdapter.SourceRow> rows = adapter.fetch("2026-12");
            assertEquals(1, rows.size());
            assertEquals("i01", rows.get(0).indicatorCode());
            assertEquals(0.47d, rows.get(0).value());
            assertFalse(rows.get(0).toString().contains("DEMO-9"), "患者标识不得进入适配器输出");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpAdapterRetriesOnceThenFailsLocatably() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/broken", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpJsonAdapter adapter = new HttpJsonAdapter(
                    "http://127.0.0.1:" + port + "/broken", new ObjectMapper());
            ExternalSourceAdapter.AdapterException error = assertThrows(
                    ExternalSourceAdapter.AdapterException.class, () -> adapter.fetch("2026-12"));
            assertEquals(2, calls.get(), "失败应只重试一次");
            assertTrue(error.getMessage().contains("500"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registryRefusesUnconfiguredAdapterInsteadOfPretending() {
        AdapterRegistry registry = new AdapterRegistry("", "", new ObjectMapper());
        assertTrue(registry.list().isEmpty());
        V2Exception error = assertThrows(V2Exception.class, () -> registry.fetch("http-json", "2026-12"));
        assertEquals(404, error.status);
        assertTrue(error.getMessage().contains("未配置"));
    }

    @Test
    void registryExposesConfiguredAdapters() throws IOException {
        Path dir = Files.createTempDirectory("adapter-registry-");
        AdapterRegistry registry = new AdapterRegistry(dir.toString(), "", new ObjectMapper());
        assertEquals(1, registry.list().size());
        assertEquals("csv-file", registry.list().get(0).get("code"));
    }
}
