package cn.demo.review.v2.platform;

import cn.demo.review.domain.StandardPack;
import cn.demo.review.v2.standard.StandardService;
import cn.demo.review.v2.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 演示种子：医院、科室、数据源与演示标准包。
 * 合成数据只在本类与合成适配器里出现，核心业务不依赖它。
 * 只在 demo profile 执行；prod profile 不写入任何合成数据。
 */
@Component
public class V2Seeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(V2Seeder.class);

    private final ReviewProfile profile;
    private final JdbcTemplate jdbc;
    private final StandardService standards;
    private final ObjectMapper json;

    public V2Seeder(ReviewProfile profile, JdbcTemplate jdbc, StandardService standards, ObjectMapper json) {
        this.profile = profile;
        this.jdbc = jdbc;
        this.standards = standards;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!profile.isDemo()) {
            log.info("prod profile：跳过演示种子，不写入合成数据");
            return;
        }
        seedHospitals();
        seedSources();
        seedStandard();
    }

    private void seedHospitals() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hospital", Integer.class);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO hospital (id, name, level_name) VALUES (?,?,?)",
                "H001", "江城市第一人民医院（虚构）", "三级甲等（演示）");
        jdbc.update("INSERT INTO hospital (id, name, level_name) VALUES (?,?,?)",
                "H002", "演示第二院区（虚构）", "三级乙等（演示）");
        String[][] departments = {
                {"CARD", "心血管内科"}, {"SURG", "普通外科"}, {"NEUR", "神经内科"},
                {"ORTH", "骨科"}, {"RESP", "呼吸内科"}, {"GYNE", "妇产科"}};
        for (String[] d : departments) {
            jdbc.update("INSERT INTO department (hospital_id, code, name) VALUES (?,?,?)", "H001", d[0], d[1]);
            jdbc.update("INSERT INTO department (hospital_id, code, name) VALUES (?,?,?)", "H002", d[0], d[1]);
        }
        log.info("seeded demo hospitals and departments");
    }

    private void seedSources() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM data_source WHERE hospital_id=?", Integer.class, "H001");
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO data_source (id, hospital_id, code, name, kind, active) VALUES (?,?,?,?,?,?)",
                "src-synth-h001", "H001", "SYNTHETIC-DEMO", "合成演示数据源", "synthetic", 1);
        jdbc.update("INSERT INTO data_source (id, hospital_id, code, name, kind, active) VALUES (?,?,?,?,?,?)",
                "src-csv-h001", "H001", "CSV-IMPORT", "CSV 导入适配器", "csv", 1);
    }

    private void seedStandard() {
        Integer packages = jdbc.queryForObject("SELECT COUNT(*) FROM standard_package", Integer.class);
        if (packages != null && packages > 0) return;
        StandardPack pack = StandardPack.load();
        AuthContext system = new AuthContext("system-seed", "H001", null, Set.of("review_office"), "demo");
        Map<String, Object> imported = standards.importPack(system,
                json.valueToTree(Map.of("pack", pack)), "seed");
        String versionId = (String) imported.get("id");
        standards.publish(system, versionId, "seed");
        log.info("seeded demo standard package versionId={}", versionId);
        seedSecondHospitalTask(versionId);
    }

    /** 为跨医院隔离验证准备一条另一家医院的任务，正常演示不会看到它。 */
    private void seedSecondHospitalTask(String versionId) {
        List<String> clauses = jdbc.query("SELECT code FROM standard_clause WHERE version_id=? ORDER BY code",
                (rs, i) -> rs.getString(1), versionId);
        if (clauses.isEmpty()) return;
        jdbc.update("INSERT INTO review_task (id, hospital_id, version_id, clause_code, dept_code, title,"
                        + " assignee_post, priority, due_date, status, return_reason, request_key, created_by,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "task-h002-seed", "H002", versionId, clauses.get(0), "CARD", "另一医院演示任务",
                "科室质控员", "normal", "2026-12-31", "todo", "", "seed-h002", "system-seed",
                "2026-10-30", "2026-10-30");
    }
}
