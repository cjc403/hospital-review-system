package cn.demo.review.service;

import cn.demo.review.domain.Model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Minimal standards based DOCX package. Inputs are exclusively the saved immutable snapshot. */
public final class DocxReport {
    private DocxReport() {}
    public static byte[] render(ReportSnapshot snapshot) throws IOException {
        ScoreRun r=snapshot.scoreRun;StringBuilder body=new StringBuilder();
        paragraph(body,"医院等级评审演示试算报告",true);
        paragraph(body,"演示环境 / 合成数据 / 不作为正式评审结论",true);
        paragraph(body,snapshot.meta.hospitalName,false);
        paragraph(body,"标准："+snapshot.meta.standard,false);
        paragraph(body,"试算编号："+r.id+"；创建时间："+r.createdAt,false);
        paragraph(body,"计算周期："+r.cycleStart+"—"+r.cycleEnd+"（"+r.yearCount+"年）；固定合成年度数据",false);
        paragraph(body,"符合："+r.conformCount+" / "+r.includedCount+"；符合比例："+value(r.ratio*100)+"%；模拟等次："+grade(r.grade),true);
        paragraph(body,"所有结果trial=true；2026年为完整年模拟样本，非实际全年数据。年度事实使用固定样本，不以月度补录冒充全年事实。",false);
        for(String warning:r.warnings)paragraph(body,"提示："+warning,false);
        paragraph(body,"一、年度合并计算快照",true);
        StringBuilder table=new StringBuilder("<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr>");
        row(table,"指标","年度值","合并值","符合 / 原因");
        for(ScoreItem i:r.items){StringBuilder years=new StringBuilder();for(Annual a:i.yearValues)years.append(a.year).append(": ").append(value(a.value)).append("; ");row(table,i.name,years.toString(),value(i.mergedValue)+i.unit,i.conform==null?"不纳入 / "+i.reasonCode:(i.conform?"符合 / ":"不符合 / ")+i.reasonCode);}
        table.append("</w:tbl>");body.append(table);
        paragraph(body,"二、前置结论快照",true);
        for(Prereq p:snapshot.prerequisites)paragraph(body,p.code+" "+p.title+"；结论="+p.compliance+"；流程="+p.workflow+"；佐证数="+p.evidenceCount+"；说明："+p.statement,false);
        paragraph(body,"三、条款抽查快照",true);
        if(snapshot.auditSamples.isEmpty())paragraph(body,"创建本次试算时尚无条款抽查；数据真实性未确认。",false);
        for(AuditSample a:snapshot.auditSamples){paragraph(body,a.id+"；周期="+a.period+"；错误条款="+a.errorCount+"/"+a.sampleCount+"；错误率="+value(a.errorRate*100)+"%",false);for(AuditItem i:a.items)paragraph(body,"  "+i.name+"：首页="+value(i.homeValue)+"；核查="+value(i.hqmsValue)+"；相对偏差="+value(i.deviation==null?null:i.deviation*100)+"%；"+i.result,false);}
        paragraph(body,"病案差异清单不计入条款错误率。条款相对偏差>=10%为错误，错误条款率严格>10%才触发延期。",false);
        paragraph(body,"本报告绑定创建试算时的年度计算、前置结论及抽查快照，后续补录、复核、采纳或新增抽查不会改写此报告。",false);
        String document="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"850\" w:bottom=\"1134\" w:left=\"850\"/></w:sectPr></w:body></w:document>";
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            entry(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(zip,"word/document.xml",document);zip.finish();return out.toByteArray();
        }
    }
    private static void entry(ZipOutputStream zip,String name,String value)throws IOException{ZipEntry e=new ZipEntry(name);e.setTime(0);zip.putNextEntry(e);zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static String xml(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","");}
    private static void paragraph(StringBuilder b,String text,boolean heading){b.append("<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/>");if(heading)b.append("<w:b/><w:sz w:val=\"26\"/>");else b.append("<w:sz w:val=\"20\"/>");b.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r></w:p>");}
    private static void row(StringBuilder b,String... cells){b.append("<w:tr>");for(String cell:cells){b.append("<w:tc><w:tcPr><w:tcW w:w=\"2500\" w:type=\"dxa\"/></w:tcPr>");paragraph(b,cell,false);b.append("</w:tc>");}b.append("</w:tr>");}
    private static String value(Double v){return v==null?"—":java.math.BigDecimal.valueOf(v).setScale(4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    private static String grade(String g){return g==null?"未达到样例等次":switch(g){case "jia"->"甲等（试算）";case "yi"->"乙等（试算）";case "bing"->"丙等（试算）";default->"延期（试算）";};}
}
