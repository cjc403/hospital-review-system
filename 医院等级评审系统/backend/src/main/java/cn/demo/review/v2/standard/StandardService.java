package cn.demo.review.v2.standard;

import cn.demo.review.domain.IndicatorDefinition;
import cn.demo.review.domain.StandardPack;
import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.platform.Hashes;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 标准与条款中心：包/版本/章节/条款/指标/前置要求。
 * 发布后的版本不提供任何修改入口，修订只能生成新版本，符合"不可变 + 可审计"。
 */
@Service
public class StandardService {
    private static final Set<String> DIRECTIONS = Set.of("high", "low");
    private static final Set<String> SCORE_TYPES = Set.of("all_or_nothing", "interval_trend");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public StandardService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    // ---------- 读 ----------

    public Map<String, Object> listPackages() {
        List<Map<String, Object>> packages = jdbc.query(
                "SELECT id, code, title, issuer, source_locator FROM standard_package ORDER BY code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("code", rs.getString("code"));
                    m.put("title", rs.getString("title"));
                    m.put("issuer", rs.getString("issuer"));
                    m.put("sourceLocator", rs.getString("source_locator"));
                    m.put("versions", versionsOf(rs.getString("id")));
                    return m;
                });
        return Map.of("packages", packages);
    }

    private List<Map<String, Object>> versionsOf(String packageId) {
        return jdbc.query("SELECT id, version_no, status, published_on, effective_from, checksum, created_at"
                        + " FROM standard_version WHERE package_id=? ORDER BY version_no DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("status", rs.getString("status"));
                    m.put("publishedOn", rs.getString("published_on"));
                    m.put("effectiveFrom", rs.getString("effective_from"));
                    m.put("checksum", rs.getString("checksum"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, packageId);
    }

    public Map<String, Object> versionDetail(String versionId) {
        Map<String, Object> version = versionRow(versionId);
        List<Map<String, Object>> chapters = jdbc.query(
                "SELECT code, title, sort_no FROM standard_chapter WHERE version_id=? ORDER BY sort_no, code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("title", rs.getString("title"));
                    m.put("sort", rs.getInt("sort_no"));
                    return m;
                },
                versionId);
        List<Map<String, Object>> clauses = jdbc.query(
                "SELECT code, chapter_code, title, requirement_text, evidence_type, owner_scope, evaluation_method, source_locator"
                        + " FROM standard_clause WHERE version_id=? ORDER BY code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("chapterCode", rs.getString("chapter_code"));
                    m.put("title", rs.getString("title"));
                    m.put("requirement", rs.getString("requirement_text"));
                    m.put("evidenceType", rs.getString("evidence_type"));
                    m.put("ownerScope", rs.getString("owner_scope"));
                    m.put("evaluationMethod", rs.getString("evaluation_method"));
                    m.put("sourceLocator", rs.getString("source_locator"));
                    return m;
                }, versionId);
        List<Map<String, Object>> indicators = jdbc.query(
                "SELECT code, name, clause_code, chapter_code, dimension, unit, direction, score_type, annual_agg,"
                        + " collect_mode, target_value, warn_value FROM indicator_definition WHERE version_id=? ORDER BY code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("clauseCode", rs.getString("clause_code"));
                    m.put("chapterCode", rs.getString("chapter_code"));
                    m.put("dimension", rs.getString("dimension"));
                    m.put("unit", rs.getString("unit"));
                    m.put("direction", rs.getString("direction"));
                    m.put("scoreType", rs.getString("score_type"));
                    m.put("annualAgg", rs.getString("annual_agg"));
                    m.put("collectMode", rs.getString("collect_mode"));
                    m.put("target", rs.getObject("target_value"));
                    m.put("warn", rs.getObject("warn_value"));
                    return m;
                }, versionId);
        List<Map<String, Object>> prerequisites = jdbc.query(
                "SELECT code, section, title, statement_text, system_generated FROM prereq_definition"
                        + " WHERE version_id=? ORDER BY code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("section", rs.getString("section"));
                    m.put("title", rs.getString("title"));
                    m.put("statement", rs.getString("statement_text"));
                    m.put("systemGenerated", rs.getInt("system_generated") == 1);
                    return m;
                }, versionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("chapters", chapters);
        result.put("clauses", clauses);
        result.put("indicators", indicators);
        result.put("prerequisites", prerequisites);
        result.put("counts", Map.of("chapters", chapters.size(), "clauses", clauses.size(),
                "indicators", indicators.size(), "prerequisites", prerequisites.size()));
        return result;
    }

    public Map<String, Object> versionRow(String versionId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT v.id, v.package_id, p.code AS package_code, v.version_no, v.status, v.published_on,"
                        + " v.effective_from, v.applies_to, v.note, v.checksum, v.created_at"
                        + " FROM standard_version v JOIN standard_package p ON p.id=v.package_id WHERE v.id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("packageId", rs.getString("package_id"));
                    m.put("packageCode", rs.getString("package_code"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("status", rs.getString("status"));
                    m.put("publishedOn", rs.getString("published_on"));
                    m.put("effectiveFrom", rs.getString("effective_from"));
                    m.put("appliesTo", rs.getString("applies_to"));
                    m.put("note", rs.getString("note"));
                    m.put("checksum", rs.getString("checksum"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, versionId);
        if (rows.isEmpty()) throw V2Exception.notFound("标准版本不存在");
        return rows.get(0);
    }

    public String requirePublished(String versionId) {
        Map<String, Object> row = versionRow(versionId);
        if (!"published".equals(row.get("status"))) throw V2Exception.conflict("该标准版本尚未发布");
        return (String) row.get("packageId");
    }

    // ---------- 写 ----------

    @Transactional
    public Map<String, Object> importPack(AuthContext ctx, JsonNode body, String requestId) {
        JsonNode packNode = body.has("pack") ? body.get("pack") : body;
        StandardPack pack;
        try {
            pack = json.treeToValue(packNode, StandardPack.class);
        } catch (Exception e) {
            throw V2Exception.badRequest("标准包结构不合法，应为 {package,version,chapters,clauses,indicators,prerequisites}");
        }
        List<Map<String, String>> issues = validate(pack);
        if (!issues.isEmpty()) {
            throw new V2Exception(400, "validation_failed", "标准包校验未通过，共 " + issues.size() + " 项", issues);
        }
        String checksum = Hashes.sha256Hex(packNode.toString());
        String packageId = packageId(pack.pkg.code);
        if (packageId == null) {
            packageId = "pkg-" + UUID.randomUUID();
            jdbc.update("INSERT INTO standard_package (id, code, title, issuer, source_locator) VALUES (?,?,?,?,?)",
                    packageId, pack.pkg.code, pack.pkg.title, pack.pkg.issuer, pack.pkg.sourceLocator);
        }
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM standard_version WHERE package_id=?", Integer.class, packageId);
        String versionId = "stdv-" + UUID.randomUUID();
        jdbc.update("INSERT INTO standard_version (id, package_id, version_no, status, published_on, effective_from,"
                        + " applies_to, note, checksum, created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                versionId, packageId, next, "draft", null, pack.version.effectiveFrom,
                pack.version.appliesTo, pack.version.note, checksum, LocalDate.now().toString(), ctx.userId());
        insertRows(versionId, pack);
        audit.record(ctx, requestId, "导入标准包", "standard_version", versionId,
                "包=" + pack.pkg.code + " 版本=" + next + " 指标=" + pack.indicators.size() + " 条款=" + pack.clauses.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", versionId);
        result.put("versionNo", next);
        result.put("status", "draft");
        result.put("checksum", checksum);
        result.put("counts", Map.of("chapters", pack.chapters.size(), "clauses", pack.clauses.size(),
                "indicators", pack.indicators.size(), "prerequisites", pack.prerequisites.size()));
        return result;
    }

    @Transactional
    public Map<String, Object> publish(AuthContext ctx, String versionId, String requestId) {
        Map<String, Object> row = versionRow(versionId);
        if ("published".equals(row.get("status"))) throw V2Exception.immutable("该版本已发布，不可重复发布");
        if ("retired".equals(row.get("status"))) throw V2Exception.immutable("已废止版本不可再发布");
        String today = LocalDate.now().toString();
        jdbc.update("UPDATE standard_version SET status='published', published_on=COALESCE(published_on, ?) WHERE id=?",
                today, versionId);
        audit.record(ctx, requestId, "发布标准版本", "standard_version", versionId, "版本号=" + row.get("versionNo"));
        return versionRow(versionId);
    }

    @Transactional
    public Map<String, Object> revision(AuthContext ctx, String versionId, String requestId, String note) {
        Map<String, Object> source = versionRow(versionId);
        String packageId = (String) source.get("packageId");
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM standard_version WHERE package_id=?", Integer.class, packageId);
        String newId = "stdv-" + UUID.randomUUID();
        jdbc.update("INSERT INTO standard_version (id, package_id, version_no, status, published_on, effective_from,"
                        + " applies_to, note, checksum, created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                newId, packageId, next, "draft", null, source.get("effectiveFrom"), source.get("appliesTo"),
                note == null ? "修订自版本 " + source.get("versionNo") : note,
                source.get("checksum"), LocalDate.now().toString(), ctx.userId());
        jdbc.update("INSERT INTO standard_chapter (version_id, code, title, sort_no)"
                + " SELECT ?, code, title, sort_no FROM standard_chapter WHERE version_id=?", newId, versionId);
        jdbc.update("INSERT INTO standard_clause (version_id, code, chapter_code, title, requirement_text, evidence_type,"
                + " owner_scope, evaluation_method, source_locator, sort_no)"
                + " SELECT ?, code, chapter_code, title, requirement_text, evidence_type, owner_scope,"
                + " evaluation_method, source_locator, sort_no FROM standard_clause WHERE version_id=?", newId, versionId);
        jdbc.update("INSERT INTO indicator_definition (version_id, code, name, clause_code, chapter_code, dimension, unit,"
                + " direction, score_type, annual_agg, collect_mode, target_value, warn_value, numerator_rule,"
                + " denominator_rule, include_rule, exclude_rule)"
                + " SELECT ?, code, name, clause_code, chapter_code, dimension, unit, direction, score_type, annual_agg,"
                + " collect_mode, target_value, warn_value, numerator_rule, denominator_rule, include_rule, exclude_rule"
                + " FROM indicator_definition WHERE version_id=?", newId, versionId);
        jdbc.update("INSERT INTO prereq_definition (version_id, code, section, title, statement_text, system_generated)"
                + " SELECT ?, code, section, title, statement_text, system_generated FROM prereq_definition"
                + " WHERE version_id=?", newId, versionId);
        audit.record(ctx, requestId, "修订标准版本", "standard_version", newId,
                "来源版本=" + versionId + " 新版本号=" + next);
        return versionRow(newId);
    }

    void insertRows(String versionId, StandardPack pack) {
        int chapterSort = 0;
        for (StandardPack.Chapter c : pack.chapters) {
            jdbc.update("INSERT INTO standard_chapter (version_id, code, title, sort_no) VALUES (?,?,?,?)",
                    versionId, c.code, c.title, c.sort != 0 ? c.sort : ++chapterSort);
        }
        int clauseSort = 0;
        for (StandardPack.Clause c : pack.clauses) {
            jdbc.update("INSERT INTO standard_clause (version_id, code, chapter_code, title, requirement_text,"
                            + " evidence_type, owner_scope, evaluation_method, source_locator, sort_no)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    versionId, c.code, c.chapterCode, c.title, c.requirement, c.evidenceType,
                    c.ownerScope, c.evaluationMethod, c.sourceLocator, ++clauseSort);
        }
        for (IndicatorDefinition d : pack.indicators) {
            jdbc.update("INSERT INTO indicator_definition (version_id, code, name, clause_code, chapter_code, dimension,"
                            + " unit, direction, score_type, annual_agg, collect_mode, target_value, warn_value,"
                            + " numerator_rule, denominator_rule, include_rule, exclude_rule)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    versionId, d.code, d.name, d.clauseCode, d.chapterCode, d.dimension, d.unit, d.direction,
                    d.scoreType, d.annualAgg, d.collectMode, d.target, d.warn, d.numeratorRule,
                    d.denominatorRule, d.includeRule, d.excludeRule);
        }
        for (StandardPack.PrerequisiteDef p : pack.prerequisites) {
            jdbc.update("INSERT INTO prereq_definition (version_id, code, section, title, statement_text, system_generated)"
                            + " VALUES (?,?,?,?,?,?)",
                    versionId, p.code, p.section, p.title, p.statement, p.systemGenerated ? 1 : 0);
        }
    }

    // ---------- 校验 ----------

    List<Map<String, String>> validate(StandardPack pack) {
        List<Map<String, String>> issues = new ArrayList<>();
        if (pack.pkg == null || blank(pack.pkg.code)) issues.add(issue("package.code", "required", "标准包编码必填"));
        if (pack.pkg == null || blank(pack.pkg.title)) issues.add(issue("package.title", "required", "标准包标题必填"));
        if (pack.version == null) issues.add(issue("version", "required", "缺少版本信息"));
        if (pack.chapters.isEmpty()) issues.add(issue("chapters", "required", "至少需要 1 个章节"));

        Set<String> chapterCodes = new HashSet<>();
        for (int i = 0; i < pack.chapters.size(); i++) {
            StandardPack.Chapter c = pack.chapters.get(i);
            if (blank(c.code)) issues.add(issue("chapters[" + i + "].code", "required", "章节编码必填"));
            else if (!chapterCodes.add(c.code)) issues.add(issue("chapters[" + i + "].code", "duplicate", "章节编码重复：" + c.code));
            if (blank(c.title)) issues.add(issue("chapters[" + i + "].title", "required", "章节标题必填"));
        }

        Set<String> clauseCodes = new HashSet<>();
        for (int i = 0; i < pack.clauses.size(); i++) {
            StandardPack.Clause c = pack.clauses.get(i);
            String path = "clauses[" + i + "]";
            if (blank(c.code)) issues.add(issue(path + ".code", "required", "条款编码必填"));
            else if (!clauseCodes.add(c.code)) issues.add(issue(path + ".code", "duplicate", "条款编码重复：" + c.code));
            if (!blank(c.chapterCode) && !chapterCodes.contains(c.chapterCode)) {
                issues.add(issue(path + ".chapterCode", "missing_parent", "条款引用的章节不存在：" + c.chapterCode));
            }
            if (blank(c.title)) issues.add(issue(path + ".title", "required", "条款标题必填"));
        }

        Set<String> indicatorCodes = new HashSet<>();
        for (int i = 0; i < pack.indicators.size(); i++) {
            IndicatorDefinition d = pack.indicators.get(i);
            String path = "indicators[" + i + "]";
            if (blank(d.code)) issues.add(issue(path + ".code", "required", "指标编码必填"));
            else if (!indicatorCodes.add(d.code)) issues.add(issue(path + ".code", "duplicate", "指标编码重复：" + d.code));
            if (blank(d.name)) issues.add(issue(path + ".name", "required", "指标名称必填"));
            if (!blank(d.clauseCode) && !clauseCodes.contains(d.clauseCode)) {
                issues.add(issue(path + ".clauseCode", "missing_parent", "指标引用的条款不存在：" + d.clauseCode));
            }
            if (!DIRECTIONS.contains(d.direction)) {
                issues.add(issue(path + ".direction", "enum", "direction 只能是 high 或 low"));
            }
            if (!SCORE_TYPES.contains(d.scoreType)) {
                issues.add(issue(path + ".scoreType", "enum", "scoreType 只能是 all_or_nothing 或 interval_trend"));
            }
            if ("%".equals(d.unit) && (d.target == 0 && d.warn == 0)) {
                issues.add(issue(path + ".target", "missing_threshold", "百分比指标必须给出演示目标线"));
            }
        }

        Set<String> prereqCodes = new HashSet<>();
        for (int i = 0; i < pack.prerequisites.size(); i++) {
            StandardPack.PrerequisiteDef p = pack.prerequisites.get(i);
            if (blank(p.code)) issues.add(issue("prerequisites[" + i + "].code", "required", "前置编码必填"));
            else if (!prereqCodes.add(p.code)) issues.add(issue("prerequisites[" + i + "].code", "duplicate", "前置编码重复：" + p.code));
        }
        return issues;
    }

    private String packageId(String code) {
        List<String> ids = jdbc.query("SELECT id FROM standard_package WHERE code=?",
                (rs, i) -> rs.getString(1), code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static Map<String, String> issue(String path, String rule, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("rule", rule);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
