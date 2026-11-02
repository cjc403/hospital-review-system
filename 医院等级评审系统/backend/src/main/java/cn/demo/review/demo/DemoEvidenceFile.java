package cn.demo.review.demo;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 交给正常上传服务的合成材料，第一版 TXT、第二版 DOCX，均有实际可下载字节。 */
final class DemoEvidenceFile implements MultipartFile {
    private final byte[] bytes;
    private final String fileName;
    private final String mime;

    DemoEvidenceFile(String title, int version) throws IOException {
        String text = title + "\n演示环境 / 合成数据 / 不作正式评审结论\n"
                + "材料版本：" + version + "；统计口径：与指标定义和采集批次一致\n"
                + "一、检查背景：围绕本期质量安全指标开展科室自查与资料核对。\n"
                + "二、检查范围：门诊、住院、手术及科室交接流程的虚构样本。\n"
                + "三、发现问题：薄弱环节为夜班记录完整性、时间戳与编码映射一致性。\n"
                + "四、改进措施：组织专题培训，建立责任清单，逐项核对并按月复盘。\n"
                + "五、复核方法：使用同一指标口径比较基线与后续周期，不以附件数量代替改善。\n"
                + "六、结论：本材料仅支持合成场景的流程演示，不代表真实医院情况。\n"
                + "复核角色：科室质控员（虚构）、质量管理办公室（虚构）。\n"
                + (version > 1 ? "修订记录：补齐抽查依据、责任岗位与效果复核页，旧版本继续保留。\n" : "待补充：复核页将在第二版中形成。\n");
        fileName = title + (version > 1 ? "-复核版.docx" : "-初稿.txt");
        mime = version > 1 ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "text/plain;charset=UTF-8";
        bytes = version > 1 ? docx(text) : text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] docx(String text) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            var body = new StringBuilder();
            for (String line : text.split("\n")) body.append("<w:p><w:r><w:rPr><w:rFonts w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr><w:t>")
                    .append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</w:t></w:r></w:p>");
            entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>");
        }
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        var entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    public String getName() { return "file"; }
    public String getOriginalFilename() { return fileName; }
    public String getContentType() { return mime; }
    public boolean isEmpty() { return bytes.length == 0; }
    public long getSize() { return bytes.length; }
    public byte[] getBytes() { return bytes.clone(); }
    public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
    public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
}
