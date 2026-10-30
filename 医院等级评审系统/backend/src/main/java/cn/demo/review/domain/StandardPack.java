package cn.demo.review.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示标准包：指标、章节、条款、前置要求的唯一结构化来源。
 * v0.1 种子数据与 v0.2 标准导入共用此结构，避免同一份口径写两遍。
 */
public final class StandardPack {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE = "/standards/demo-standard-v1.json";

    @JsonProperty("package") public PackageInfo pkg;
    public VersionInfo version;
    public List<Chapter> chapters = new ArrayList<>();
    public List<Clause> clauses = new ArrayList<>();
    public List<IndicatorDefinition> indicators = new ArrayList<>();
    public List<PrerequisiteDef> prerequisites = new ArrayList<>();

    public static class PackageInfo {
        public String code;
        public String title;
        public String issuer;
        public String sourceLocator;
    }

    public static class VersionInfo {
        public int versionNo;
        public String publishedOn;
        public String effectiveFrom;
        public String appliesTo;
        public String note;
    }

    public static class Chapter {
        public String code;
        public String title;
        public int sort;
    }

    public static class Clause {
        public String code;
        public String chapterCode;
        public String title;
        public String requirement;
        public String evidenceType;
        public String ownerScope;
        public String evaluationMethod;
        public String sourceLocator;
    }

    public static class PrerequisiteDef {
        public String code;
        public String section;
        public String title;
        public String statement;
        public boolean systemGenerated;
    }

    public static StandardPack load() {
        try (InputStream stream = StandardPack.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("缺少演示标准包资源 " + RESOURCE);
            return JSON.readValue(stream, StandardPack.class);
        } catch (IOException e) {
            throw new UncheckedIOException("演示标准包读取失败", e);
        }
    }

    public IndicatorDefinition indicator(String code) {
        for (IndicatorDefinition i : indicators) if (i.code.equals(code)) return i;
        return null;
    }
}
