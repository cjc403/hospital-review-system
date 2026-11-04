package cn.demo.review.v2.evidence;

import cn.demo.review.v2.V2Exception;
import cn.demo.review.v2.platform.AuditService;
import cn.demo.review.v2.platform.Hashes;
import cn.demo.review.v2.security.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 材料资料库：版本、SHA-256、多对象关联、审核、有效期与下载授权。
 * 已上传版本没有修改或删除入口，只能追加新版本，旧版本永久可查。
 */
@Service
public class EvidenceService {
    private static final Set<String> OWNER_TYPES = Set.of("clause", "task", "indicator", "prereq", "improvement");
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "png", "jpg", "jpeg", "txt", "docx");
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final BlobStore store;
    private final AuditService audit;

    public EvidenceService(JdbcTemplate jdbc, BlobStore store, AuditService audit) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> upload(AuthContext ctx, MultipartFile file, String ownerType, String ownerId,
                                      String title, String materialType, String validUntil, String requestId) {
        ctx.requirePermission("evidence.upload");
        if (!OWNER_TYPES.contains(ownerType)) throw V2Exception.badRequest("ownerType 取值不合法");
        requireTex(ownerId, "ownerId");
        requireTex(title, "title");
        if (validUntil != null && !validUntil.isBlank()) {
            try {
                LocalDate.parse(validUntil);
            } catch (Exception e) {
                throw V2Exception.badRequest("validUntil 格式须为 YYYY-MM-DD");
            }
        }
        if(ctx.deptScoped() && !ownerVisible(ctx,ownerType,ownerId)){if(!ctx.accountSession())throw V2Exception.forbidden("只能操作本科室材料");throw V2Exception.notFound("材料关联对象不在当前授权范围");}
        if (file == null || file.isEmpty()) throw V2Exception.badRequest("附件不能为空");
        if (file.getSize() > MAX_BYTES) throw V2Exception.badRequest("附件最大5MiB");

        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) throw V2Exception.badRequest("附件缺少文件名");
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 180 || name.indexOf('\0') >= 0 || name.contains("\r") || name.contains("\n")) {
            throw V2Exception.badRequest("附件文件名不合法");
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw V2Exception.badRequest("仅允许 pdf/png/jpg/jpeg/txt/docx 附件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw V2Exception.badRequest("附件读取失败");
        }
        validateContent(ext, bytes);
        String sha256 = Hashes.sha256Hex(bytes);

        List<Map<String, Object>> existingItems = jdbc.query(
                "SELECT id, status, created_at FROM evidence_item WHERE hospital_id=? AND owner_type=? AND owner_id=? AND title=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, ctx.hospitalId(), ownerType, ownerId, title);
        String itemId;
        if (existingItems.isEmpty()) {
            itemId = "evi-" + UUID.randomUUID();
            jdbc.update("INSERT INTO evidence_item (id, hospital_id, owner_type, owner_id, title, material_type, status,"
                            + " valid_until, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    itemId, ctx.hospitalId(), ownerType, ownerId, title, materialType, "pending",
                    validUntil, ctx.userId(), now());
        } else {
            itemId = (String) existingItems.get(0).get("id");
            List<String> sameSha = jdbc.query("SELECT id FROM evidence_version WHERE item_id=? AND sha256=?",
                    (rs, i) -> rs.getString(1), itemId, sha256);
            if (!sameSha.isEmpty()) {
                Map<String, Object> version = version(sameSha.get(0));
                audit.record(ctx, requestId, "重复上传材料（返回既有版本）", "evidence_item", itemId, "sha256 相同");
                return Map.of("item", item(ctx.hospitalId(), itemId), "version", version, "idempotent", true);
            }
        }
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM evidence_version WHERE item_id=?", Integer.class, itemId);
        String storageKey = store.put(bytes);
        String versionId = "evv-" + UUID.randomUUID();
        jdbc.update("INSERT INTO evidence_version (id, item_id, version_no, file_name, content_type, size_bytes, sha256,"
                        + " storage_key, uploaded_by, uploaded_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                versionId, itemId, next, name, file.getContentType(), bytes.length, sha256,
                storageKey, ctx.userId(), now());
        audit.record(ctx, requestId, "上传材料版本", "evidence_item", itemId,
                "版本=" + next + " 类型=" + materialType + " 大小=" + bytes.length);
        return Map.of("item", item(ctx.hospitalId(), itemId), "version", version(versionId), "idempotent", false);
    }

    public Map<String, Object> list(AuthContext ctx, String ownerType, String ownerId) {
        StringBuilder sql = new StringBuilder("SELECT i.id, i.owner_type, i.owner_id, i.title, i.material_type,"
                + " i.status, i.valid_until, i.created_by, i.created_at FROM evidence_item i WHERE i.hospital_id=?");
        List<Object> args = new ArrayList<>();
        args.add(ctx.hospitalId());
        if (ownerType != null && !ownerType.isBlank()) {
            sql.append(" AND i.owner_type=?");
            args.add(ownerType);
        }
        if (ownerId != null && !ownerId.isBlank()) {
            sql.append(" AND i.owner_id=?");
            args.add(ownerId);
        }
        sql.append(" ORDER BY i.created_at DESC");
        List<Map<String, Object>> items = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("ownerType", rs.getString("owner_type"));
            m.put("ownerId", rs.getString("owner_id"));
            m.put("title", rs.getString("title"));
            m.put("materialType", rs.getString("material_type"));
            m.put("status", rs.getString("status"));
            m.put("validUntil", rs.getString("valid_until"));
            m.put("createdBy", rs.getString("created_by"));
            m.put("createdAt", rs.getString("created_at"));
            m.put("versionCount", versionCount(rs.getString("id")));
            return m;
        }, args.toArray());
        if (ctx.deptCode() != null) {
            items = items.stream().filter(m -> readableByDept(ctx, (String) m.get("id"))).toList();
        }
        return Map.of("items", items);
    }

    public Map<String, Object> detail(AuthContext ctx, String itemId) {
        if (ctx.deptCode() != null && !readableByDept(ctx, itemId)) {
            throw V2Exception.notFound("材料不存在或不在可见范围");
        }
        List<Map<String, Object>> versions = jdbc.query(
                "SELECT id, version_no, file_name, content_type, size_bytes, sha256, uploaded_by, uploaded_at"
                        + " FROM evidence_version WHERE item_id=? ORDER BY version_no DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("fileName", rs.getString("file_name"));
                    m.put("contentType", rs.getString("content_type"));
                    m.put("size", rs.getLong("size_bytes"));
                    m.put("sha256", rs.getString("sha256"));
                    m.put("uploadedBy", rs.getString("uploaded_by"));
                    m.put("uploadedAt", rs.getString("uploaded_at"));
                    return m;
                }, itemId);
        return Map.of("item", item(ctx.hospitalId(), itemId), "versions", versions);
    }

    @Transactional
    public Map<String, Object> review(AuthContext ctx, String itemId, String decision, String note, String requestId) {
        ctx.requirePermission("evidence.review");
        if(ctx.deptScoped()&&!readableByDept(ctx,itemId))throw V2Exception.notFound("材料不在当前授权范围");
        if (!Set.of("approved", "rejected").contains(decision)) {
            throw V2Exception.badRequest("decision 只能是 approved 或 rejected");
        }
        Map<String, Object> item = item(ctx.hospitalId(), itemId);
        if (!"pending".equals(item.get("status"))) throw V2Exception.immutable("该材料已审核，不可重复审核");
        jdbc.update("UPDATE evidence_item SET status=? WHERE id=? AND hospital_id=?", decision, itemId, ctx.hospitalId());
        audit.record(ctx, requestId, "审核材料", "evidence_item", itemId,
                decision + (note == null || note.isBlank() ? "" : "；意见=" + note));
        return item(ctx.hospitalId(), itemId);
    }

    public Download download(AuthContext ctx, String versionId, String requestId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT v.id, v.file_name, v.content_type, v.storage_key, i.id AS item_id, i.hospital_id"
                        + " FROM evidence_version v JOIN evidence_item i ON i.id=v.item_id WHERE v.id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("fileName", rs.getString("file_name"));
                    m.put("contentType", rs.getString("content_type"));
                    m.put("storageKey", rs.getString("storage_key"));
                    m.put("itemId", rs.getString("item_id"));
                    m.put("hospitalId", rs.getString("hospital_id"));
                    return m;
                }, versionId);
        if (rows.isEmpty()) throw V2Exception.notFound("材料版本不存在");
        Map<String, Object> row = rows.get(0);
        if (!ctx.hospitalId().equals(row.get("hospitalId"))) throw V2Exception.notFound("材料版本不存在");
        if (ctx.deptCode() != null && !readableByDept(ctx, (String) row.get("itemId"))) {
            throw V2Exception.notFound("材料不存在或不在可见范围");
        }
        byte[] content = store.get((String) row.get("storageKey"));
        audit.record(ctx, requestId, "下载材料", "evidence_version", versionId, "文件=" + row.get("fileName"));
        return new Download((String) row.get("fileName"), (String) row.get("contentType"), content);
    }

    public record Download(String fileName, String contentType, byte[] bytes) {}

    private boolean readableByDept(AuthContext ctx, String itemId) {
        List<Map<String,Object>> owners=jdbc.queryForList("SELECT owner_type,owner_id FROM evidence_item WHERE id=? AND hospital_id=?",itemId,ctx.hospitalId());
        if(owners.isEmpty())return false;
        return ownerVisible(ctx,String.valueOf(owners.get(0).get("owner_type")),String.valueOf(owners.get(0).get("owner_id")));
    }

    private boolean ownerVisible(AuthContext ctx,String type,String id) {
        if(type.equals("task")) {
            var rows=jdbc.queryForList("SELECT dept_code,clause_code FROM review_task WHERE id=? AND hospital_id=?",id,ctx.hospitalId());
            return !rows.isEmpty() && Objects.equals(ctx.deptCode(),rows.get(0).get("dept_code")) && ctx.clauseVisible(String.valueOf(rows.get(0).get("clause_code")));
        }
        if(type.equals("improvement")&&!ctx.has("expert")) {
            var rows=jdbc.queryForList("SELECT alert_id,source_type,source_id FROM v3_improvement WHERE id=? AND hospital_id=?",id,ctx.hospitalId());
            if(rows.isEmpty())return false;var row=rows.get(0);
            String origin=String.valueOf(row.get("source_type")), source=String.valueOf(row.get("source_id"));
            if(row.get("alert_id")!=null){origin="alert";source=String.valueOf(row.get("alert_id"));}
            String table=switch(origin){case "result"->"metric_result";case "task"->"review_task";case "alert"->"v3_alert";default->null;};
            if(table==null)return false;
            var depts=jdbc.queryForList("SELECT dept_code FROM "+table+" WHERE id=? AND hospital_id=?",source,ctx.hospitalId());
            return !depts.isEmpty()&&Objects.equals(ctx.deptCode(),depts.get(0).get("dept_code"));
        }
        return false;
    }

    private Map<String, Object> item(String hospitalId, String itemId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, hospital_id, owner_type, owner_id, title, material_type, status, valid_until, created_by, created_at"
                        + " FROM evidence_item WHERE id=? AND hospital_id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("hospitalId", rs.getString("hospital_id"));
                    m.put("ownerType", rs.getString("owner_type"));
                    m.put("ownerId", rs.getString("owner_id"));
                    m.put("title", rs.getString("title"));
                    m.put("materialType", rs.getString("material_type"));
                    m.put("status", rs.getString("status"));
                    m.put("validUntil", rs.getString("valid_until"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }, itemId, hospitalId);
        if (rows.isEmpty()) throw V2Exception.notFound("材料不存在或不在可见范围");
        return rows.get(0);
    }

    private Map<String, Object> version(String versionId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, item_id, version_no, file_name, content_type, size_bytes, sha256, uploaded_by, uploaded_at"
                        + " FROM evidence_version WHERE id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("itemId", rs.getString("item_id"));
                    m.put("versionNo", rs.getInt("version_no"));
                    m.put("fileName", rs.getString("file_name"));
                    m.put("contentType", rs.getString("content_type"));
                    m.put("size", rs.getLong("size_bytes"));
                    m.put("sha256", rs.getString("sha256"));
                    m.put("uploadedBy", rs.getString("uploaded_by"));
                    m.put("uploadedAt", rs.getString("uploaded_at"));
                    return m;
                }, versionId);
        if (rows.isEmpty()) throw V2Exception.notFound("材料版本不存在");
        return rows.get(0);
    }

    private int versionCount(String itemId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_version WHERE item_id=?", Integer.class, itemId);
        return count == null ? 0 : count;
    }

    private static void requireTex(String value, String field) {
        if (value == null || value.isBlank()) throw V2Exception.badRequest(field + " 不能为空");
    }

    private static String now() {
        return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }

    /** 魔数校验：扩展名与实际内容必须一致，避免改后缀上传可执行内容。 */
    static void validateContent(String ext, byte[] bytes) {
        boolean valid = switch (ext) {
            case "pdf" -> bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            case "png" -> bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8),
                    new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "jpg", "jpeg" -> bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                    && bytes[2] == (byte) 0xff;
            case "txt" -> true;
            case "docx" -> validDocx(bytes);
            default -> false;
        };
        if (!valid) throw V2Exception.badRequest("文件内容与扩展名不一致");
    }

    private static boolean validDocx(byte[] bytes) {
        boolean content = false, document = false;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("[Content_Types].xml")) content = true;
                if (entry.getName().equals("word/document.xml")) document = true;
                byte[] buffer = new byte[8192];
                for (int n; (n = zip.read(buffer)) != -1; ) {
                    expanded += n;
                    if (expanded > 20L * 1024 * 1024) throw V2Exception.badRequest("DOCX 展开内容过大");
                }
            }
        } catch (IOException e) {
            return false;
        }
        return content && document;
    }
}
