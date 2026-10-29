package cn.demo.review.service;

import cn.demo.review.domain.*;
import cn.demo.review.persistence.SnapshotRepository;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipInputStream;
import static cn.demo.review.domain.Model.*;

@Service
public class DemoService {
    private final SnapshotRepository repository;
    private final ObjectMapper json;
    private final Path uploads;
    private State state;
    public DemoService(SnapshotRepository repository,ObjectMapper json,@Value("${demo.upload-dir}") String uploadDir) throws IOException {
        this.repository=repository;this.json=json;uploads=Path.of(uploadDir).toAbsolutePath().normalize();Files.createDirectories(uploads);
        state=repository.load().orElseGet(SyntheticData::initial);repository.save(state);
    }
    private <T> T copy(T source,Class<T> type){return json.convertValue(source,type);}
    private synchronized <T> T change(String role,String action,String objectId,Function<State,T> operation) {
        State candidate=copy(state,State.class); T result=operation.apply(candidate);
        log(candidate,role,action,objectId,"演示环境 / 合成数据");repository.save(candidate);state=candidate;return result;
    }
    private static void log(State s,String role,String action,String objectId,String detail){AuditLog l=new AuditLog();l.id=id("log");l.actor=role;l.action=action;l.objectId=objectId;l.detail=detail;l.createdAt=now();s.workspace.auditLogs.add(l);}
    public static String role(String role){if(role==null || role.isBlank())return "quality";if(!Set.of("quality","leader","department","information").contains(role))throw error(403,"未知演示角色");return role;}
    private static void quality(String role){if(!role(role).equals("quality"))throw error(403,"此操作仅允许质控角色执行");}
    private static BusinessException error(int status,String message){return new BusinessException(status,message);}
    private static String id(String prefix){return prefix+"-"+UUID.randomUUID();}
    private static String now(){return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();}
    private static <T> T find(List<T> collection,String id,Function<T,String> key,String label){return collection.stream().filter(x->Objects.equals(key.apply(x),id)).findFirst().orElseThrow(()->error(404,label+"不存在"));}
    private static Indicator indicator(State s,String id){return find(s.workspace.indicators,id,i->i.id,"指标");}
    private static Prereq prereq(State s,String id){return find(s.workspace.prerequisites,id,p->p.id,"前置条款");}
    private static Improvement improvement(State s,String id){return find(s.workspace.improvements,id,i->i.id,"改进单");}
    private static String text(JsonNode body,String name){JsonNode value=body.get(name);if(value==null || !value.isTextual() || value.asText().isBlank())throw error(400,name+"不能为空");String result=value.asText().trim();if(result.length()>4000)throw error(400,name+"过长");return result;}
    private static String optional(JsonNode body,String name,String fallback){return body.has(name)?text(body,name):fallback;}
    private static String optionalText(JsonNode body,String name,String fallback){if(!body.has(name))return fallback;JsonNode v=body.get(name);if(!v.isTextual() || v.asText().length()>4000)throw error(400,name+"必须为文本且不超过4000字");return v.asText().trim();}
    private static double number(JsonNode body,String name){JsonNode value=body.get(name);if(value==null || !value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble()<0)throw error(400,name+"必须为非负有限数值");return value.asDouble();}
    private static int integer(JsonNode body,String name){JsonNode v=body.get(name);if(v==null || !v.isIntegralNumber() || !v.canConvertToInt())throw error(400,name+"必须为整数");return v.asInt();}
    private static String choice(JsonNode body,String name,String... choices){String v=text(body,name);if(!Arrays.asList(choices).contains(v))throw error(400,name+"取值不合法");return v;}
    private static String period(JsonNode body){String value=text(body,"period");try{YearMonth p=YearMonth.parse(value);if(!p.toString().equals(value) || p.isBefore(YearMonth.of(2025,10)) || p.isAfter(YearMonth.of(2026,10)))throw error(400,"演示数据周期为2025-10至2026-10");return value;}catch(DateTimeParseException e){throw error(400,"period格式须为YYYY-MM");}}
    private static String date(JsonNode body,String name){String value=text(body,name);try{if(!LocalDate.parse(value).toString().equals(value))throw error(400,name+"格式须为YYYY-MM-DD");return value;}catch(DateTimeParseException e){throw error(400,name+"格式须为YYYY-MM-DD");}}
    public synchronized Workspace workspace(String actor) {
        String role=role(actor); Workspace result=copy(state.workspace,Workspace.class);
        if(role.equals("leader") || role.equals("information"))result.reconciliations.clear();
        for(Indicator i:result.indicators){
            List<Trend> visible=i.trends.stream().filter(t->t.period.compareTo(result.meta.period)<=0).sorted(Comparator.comparing(t->t.period)).toList();
            i.trends=new ArrayList<>(visible.subList(Math.max(0,visible.size()-12),visible.size()));
        }
        if(role.equals("department")) {
            result.departments.removeIf(d->!d.code.equals("CARD"));
            for(Indicator i:result.indicators){i.departmentValues.removeIf(v->!v.code.equals("CARD"));DepartmentValue v=i.departmentValues.get(0);i.value=v.value;i.autoValue=v.value;i.numerator=v.numerator;i.denominator=v.denominator;i.light=v.light;i.source="auto";i.trends.clear();i.annualValues.clear();}
            result.manualValues.removeIf(m->!m.deptCode.equals("CARD"));result.reconciliations.removeIf(r->!r.deptName.equals("心血管内科"));result.auditSamples.clear();result.scoreRuns.clear();result.improvements.clear();result.auditLogs.clear();result.batches.clear();result.evidenceFiles.clear();
        }
        return result;
    }
    public MemberPage members(String actor,String indicatorId,String department,String memberRole,int page,int size) {
        String role=role(actor);if(role.equals("leader") || role.equals("information"))throw error(403,"该角色不可查看成员明细");
        if(!Set.of("numerator","denominator").contains(memberRole))throw error(400,"role须为numerator或denominator");
        if(page<1 || size<1 || size>100)throw error(400,"page至少为1，size须在1至100之间");
        String dept=department;
        if(role.equals("department")){if(!dept.equals("ALL") && !dept.equals("CARD"))throw error(403,"科室角色仅可查看心血管内科");dept="CARD";}
        final String selected=dept;
        return change(role,"查看脱敏成员",indicatorId,s->{
            Indicator i=indicator(s,indicatorId);
            if(!selected.equals("ALL") && s.workspace.departments.stream().noneMatch(d->d.code.equals(selected)))throw error(400,"未知科室");
            if(!SyntheticData.PATIENT_INDICATORS.contains(i.id))return new MemberPage(List.of(),0,page,size);
            var chosen=i.departmentValues.stream().filter(d->selected.equals("ALL") || selected.equals(d.code)).toList();
            int total=chosen.stream().mapToInt(d->(memberRole.equals("numerator")?d.numerator:d.denominator).intValue()).sum();
            long from=(long)(page-1)*size,to=Math.min((long)total,from+size);List<Member> items=new ArrayList<>();
            long offset=0;
            for(DepartmentValue d:chosen){int count=(memberRole.equals("numerator")?d.numerator:d.denominator).intValue();for(long idx=Math.max(0,from-offset);idx<count && offset+idx<to;idx++){int serial=(int)idx+1;String inpatient="DEMO-"+d.code+"-****%05d".formatted(serial);items.add(new Member(inpatient,s.workspace.meta.period+"-%02d".formatted(1+(serial-1)%28),d.code,d.name,serial%2==0?"女":"男",18+serial%73,"合成诊断 "+(serial%3==0?"高血压":serial%3==1?"肺炎":"骨折"),memberRole));}offset+=count;}
            return new MemberPage(items,total,page,size);
        });
    }
    public Manual submitManual(String actor,JsonNode body) {
        quality(actor);String iid=text(body,"indicatorId"),dept=text(body,"deptCode"),p=period(body),note=text(body,"note");double value=number(body,"value");
        if(!dept.equals("ALL"))throw error(400,"本轮补录仅支持ALL全院覆盖");
        return change(actor,"提交补录",iid,s->{Indicator i=indicator(s,iid);if(i.unit.equals("%") && value>100)throw error(400,"百分比补录须在0至100之间");
            boolean pending=s.workspace.manualValues.stream().anyMatch(m->m.indicatorId.equals(iid) && m.deptCode.equals(dept) && m.period.equals(p) && m.status.equals("submitted"));
            if(pending)throw error(409,"该指标该周期已有待审核补录，请先处理");
            Manual m=new Manual();m.id=id("manual");m.indicatorId=iid;m.indicatorName=i.name;m.deptCode=dept;m.period=p;m.value=value;m.note=note;m.status="submitted";m.submitter="质控演示填报员";m.submittedAt=now();s.workspace.manualValues.add(m);return m;
        });
    }
    public Manual reviewManual(String actor,String mid,JsonNode body) {
        quality(actor);String decision=choice(body,"decision","approved","rejected"),note=text(body,"note");
        return change(actor,"审核补录",mid,s->{Manual m=find(s.workspace.manualValues,mid,x->x.id,"补录");if(!m.status.equals("submitted"))throw error(409,"该补录已审核，不允许重复审核");m.status=decision;m.reviewer="质控演示审核员";m.note=m.note+"；审核意见："+note;
            if(decision.equals("approved")){Batch b=new Batch();b.id=id("manual-batch");b.period=m.period;b.status="success";b.batchKind="manual";b.indicatorCount=1;b.createdAt=now();b.note="人工补录覆盖："+m.indicatorName+"；自动事实保留";s.workspace.batches.add(b);applyOverlays(s);}
            return m;
        });
    }
    private static void applyOverlays(State s) {
        for(Indicator i:s.workspace.indicators){i.value=i.autoValue;i.source="auto";for(Manual m:s.workspace.manualValues){if(m.status.equals("approved") && m.indicatorId.equals(i.id) && m.deptCode.equals("ALL")){for(Trend t:i.trends)if(t.period.equals(m.period))t.value=m.value;if(m.period.equals(s.workspace.meta.period)){i.value=m.value;i.source="manual";}}}i.light=SyntheticData.currentLight(i,s.workspace.meta.period);}
        for(Improvement im:s.workspace.improvements)im.latestValue=indicator(s,im.indicatorId).value;
    }
    public Batch calculate(String actor,JsonNode body) {
        quality(actor);String p=period(body);
        return change(actor,"月结重跑",p,s->{Batch b=new Batch();b.id=id("auto-batch");b.period=p;b.status="success";b.batchKind="auto";b.indicatorCount=s.workspace.indicators.size();b.createdAt=now();b.note="重新计算固定合成事实；原批次和人工覆盖均保留";s.workspace.batches.add(b);s.workspace.meta.period=p;s.workspace.meta.dataAsOf=YearMonth.parse(p).atEndOfMonth().toString();for(Indicator i:s.workspace.indicators){SyntheticData.fillFacts(i,s.workspace.departments,p);i.batchId=b.id;Trend trend=i.trends.stream().filter(t->t.period.equals(p)).findFirst().orElse(null);if(trend==null){i.trends.add(new Trend(p,i.autoValue));i.trends.sort(Comparator.comparing(t->t.period));}else trend.value=i.autoValue;}
            applyOverlays(s);return b;});
    }
    public Reconcile reconcile(String actor,String rid,JsonNode body) {
        quality(actor);String status=choice(body,"status","explained","pending"),note=text(body,"note");
        return change(actor,"维护病案差异",rid,s->{Reconcile r=find(s.workspace.reconciliations,rid,x->x.id,"差异记录");r.status=status;r.note=note;return r;});
    }
    public AuditSample sample(String actor,JsonNode body) {
        quality(actor);String p=period(body);
        return change(actor,"发起条款抽查",p,s->{AuditSample a=new AuditSample();a.id=id("sample");a.period=p;a.createdAt=now();
            for(int n=0;n<10;n++){Indicator source=indicator(s,"i%02d".formatted(n+1));Indicator fact=copy(source,Indicator.class);SyntheticData.fillFacts(fact,s.workspace.departments,p);AuditItem item=new AuditItem();item.indicatorId=source.id;item.name=source.name;item.homeValue=fact.autoValue;item.hqmsValue=SyntheticData.round(fact.autoValue*(n==0?1.2:n==1 && p.compareTo("2026-10")<0?0.9:1));item.deviation=Scoring.deviation(item.homeValue,item.hqmsValue);BigDecimal home=BigDecimal.valueOf(item.homeValue),hqms=BigDecimal.valueOf(item.hqmsValue);boolean wrong=home.signum()==0?hqms.signum()!=0:hqms.subtract(home).abs().compareTo(home.abs().multiply(new BigDecimal("0.1")))>=0;item.result=wrong?"error":"matched";if(wrong)a.errorCount++;a.items.add(item);}
            a.sampleCount=a.items.size();a.errorRate=(double)a.errorCount/a.sampleCount;s.workspace.auditSamples.add(a);
            Prereq truth=prereq(s,"p30");truth.compliance=a.errorRate>0.1?"non_compliant":"compliant";truth.workflow="accepted";truth.statement="最新条款抽查 "+a.id+"：错误"+a.errorCount+"/"+a.sampleCount+"（"+SyntheticData.round(a.errorRate*100)+"%）；仅严格超过10%触发延期，病案差异不计入";return a;});
    }
    public Prereq updatePrereq(String actor,String pid,JsonNode body) {
        quality(actor);String compliance=choice(body,"compliance","compliant","non_compliant","unknown"),workflow=choice(body,"workflow","draft","submitted","accepted","rejected"),statement=text(body,"statement");
        return change(actor,"维护前置自评",pid,s->{Prereq p=prereq(s,pid);if(p.systemGenerated)throw error(403,"数据真实性由最新条款抽查自动生成，禁止人工修改");if(!compliance.equals("compliant") && p.evidenceCount<1)throw error(400,"非符合或未知结论需要说明和附件佐证");
            Set<String> allowed=switch(p.workflow){case "draft"->Set.of("draft","submitted");case "submitted"->Set.of("submitted","accepted","rejected");case "accepted"->Set.of("accepted","draft","submitted");case "rejected"->Set.of("rejected","draft","submitted");default->Set.of();};
            if(!allowed.contains(workflow))throw error(409,"前置流程不允许从"+p.workflow+"直接变为"+workflow);if(workflow.equals("accepted") && compliance.equals("unknown"))throw error(400,"未知结论不能被确认接纳");p.compliance=compliance;p.workflow=workflow;p.statement=statement;return p;});
    }
    public ScoreRun score(String actor,JsonNode body) {
        quality(actor);int start=integer(body,"cycleStart"),end=integer(body,"cycleEnd");if(start<2023 || end>2026 || start>end || end-start>3)throw error(400,"演示评审周期须在2023至2026年之间且包含1至4年");
        return change(actor,"创建模拟等次",start+"-"+end,s->{ScoreRun r=new ScoreRun();r.id=id("score");r.cycleStart=start;r.cycleEnd=end;r.yearCount=end-start+1;r.createdAt=now();
            for(Indicator i:s.workspace.indicators){ScoreItem item=new ScoreItem();item.indicatorId=i.id;item.name=i.name;item.unit=i.unit;List<Double> values=new ArrayList<>();for(int y=start;y<=end;y++){int year=y;Double v=i.annualValues.stream().filter(a->a.year==year).findFirst().map(a->a.value).orElse(null);values.add(v);item.yearValues.add(new Annual(y,v));}var result=Scoring.evaluate(values,i.direction,i.scoreType,i.target);item.mergedValue=result.mergedValue();item.conform=result.conform();item.reasonCode=result.reasonCode();if(item.conform!=null){r.includedCount++;if(item.conform)r.conformCount++;}r.items.add(item);}
            boolean nonCompliant=s.workspace.prerequisites.stream().anyMatch(p->p.compliance.equals("non_compliant"));double rate=s.workspace.auditSamples.isEmpty()?0:s.workspace.auditSamples.get(s.workspace.auditSamples.size()-1).errorRate;r.ratio=r.includedCount==0?0:(double)r.conformCount/r.includedCount;r.grade=Scoring.grade(r.conformCount,r.includedCount,nonCompliant,rate);
            r.warnings.add("演示试算：仅覆盖"+r.includedCount+"/"+s.workspace.meta.totalMonitoringClauses+"条样例指标，低于60%，不可作为正式评审结论");
            if(end==2026)r.warnings.add("2026年为完整年模拟样本，非实际全年数据");
            if(s.workspace.prerequisites.stream().anyMatch(p->!p.compliance.equals("compliant") || !p.workflow.equals("accepted")))r.warnings.add("前置复核未全部完成：未知、未接纳或不符合均不视为已确认符合");
            if(nonCompliant)r.warnings.add("存在前置不符合，试算延期");if(rate>0.1)r.warnings.add("最新条款抽查错误率严格超过10%，试算延期");if(r.includedCount==0)r.warnings.add("无有效年度事实，不给出等次");
            s.workspace.scoreRuns.add(r);ReportSnapshot snapshot=new ReportSnapshot();snapshot.meta=copy(s.workspace.meta,Meta.class);snapshot.scoreRun=copy(r,ScoreRun.class);for(Prereq p:s.workspace.prerequisites)snapshot.prerequisites.add(copy(p,Prereq.class));for(AuditSample a:s.workspace.auditSamples)snapshot.auditSamples.add(copy(a,AuditSample.class));s.reportSnapshots.put(r.id,snapshot);return r;
        });
    }
    public ScoreRun adopt(String actor,String scoreId) {
        quality(actor);return change(actor,"采纳演示试算",scoreId,s->{ScoreRun r=find(s.workspace.scoreRuns,scoreId,x->x.id,"试算");if(r.adopted)throw error(409,"该试算已采纳");s.workspace.scoreRuns.forEach(x->x.adopted=false);r.adopted=true;return r;});
    }
    public Improvement createImprovement(String actor,JsonNode body) {
        quality(actor);String iid=text(body,"indicatorId"),origin=choice(body,"origin","red_light","worst_year"),owner=text(body,"owner"),due=date(body,"dueDate");
        return change(actor,"创建持续改进单",iid,s->{Indicator i=indicator(s,iid);if(i.value==null)throw error(400,"指标无基线值，不能创建改进单");if(origin.equals("red_light") && !i.light.equals("red"))throw error(400,"只有当前红灯指标可从红灯发起改进");if(origin.equals("worst_year") && s.workspace.scoreRuns.stream().flatMap(r->r.items.stream()).noneMatch(item->item.indicatorId.equals(iid) && item.reasonCode.equals("worst_year")))throw error(400,"尚无该指标取最差年的试算记录");Improvement im=new Improvement();im.id=id("improve");im.indicatorId=iid;im.indicatorName=i.name;im.origin=origin;im.status="opened";im.owner=owner;im.dueDate=due;im.baseline=i.value;im.target=i.target;im.latestValue=i.value;s.workspace.improvements.add(im);s.improvementBaselinePeriods.put(im.id,s.workspace.meta.period);return im;});
    }
    public Improvement updateImprovement(String actor,String imid,JsonNode body) {
        quality(actor);return change(actor,"推进持续改进",imid,s->{Improvement im=improvement(s,imid);String next=optional(body,"status",im.status);String owner=optional(body,"owner",im.owner),due=body.has("dueDate")?date(body,"dueDate"):im.dueDate;String actions=optionalText(body,"actionText",im.actionText),mode=optionalText(body,"closeMode",im.closeMode),note=optionalText(body,"closeNote",im.closeNote);
            if(im.status.equals("closed")){if(!next.equals("reopen"))throw error(409,"已关闭改进单只允许质控重新打开");next="opened";mode="";note="";}else if(next.equals("reopen"))throw error(409,"只有已关闭改进单可以重新打开");else if(!next.equals(im.status)){String expected=switch(im.status){case "opened"->"assigned";case "assigned"->"doing";case "doing"->"evidence_ready";case "evidence_ready"->"verified";case "verified"->"closed";default->"";};if(!next.equals(expected))throw error(409,"改进状态必须依次推进，当前下一步为"+expected);}
            if(Set.of("evidence_ready","verified","closed").contains(next) && (actions.isBlank() || im.evidenceCount<1))throw error(400,"证据就绪、验证和关闭均需要改进措施及附件");
            Indicator i=indicator(s,im.indicatorId);im.latestValue=i.value;
            if(next.equals("closed")){if(!Set.of("improved","not_recovered").contains(mode))throw error(400,"请选择improved或not_recovered关闭方式");if(mode.equals("improved")){String baseline=s.improvementBaselinePeriods.getOrDefault(im.id,"2026-09");boolean later=YearMonth.parse(s.workspace.meta.period).isAfter(YearMonth.parse(baseline));boolean better=i.value!=null && (i.direction.equals("high")?i.value>im.baseline:i.value<im.baseline);if(!later || !better)throw error(400,"恢复关闭需要后续周期值较基线改善；未恢复请选not_recovered并说明");}else if(note.isBlank())throw error(400,"未恢复关闭必须填写说明");}
            im.status=next;im.owner=owner;im.dueDate=due;im.actionText=actions;im.closeMode=mode;im.closeNote=note;return im;});
    }
    public Policy registerPolicy(String actor,JsonNode body) {
        String role=role(actor);if(!role.equals("quality") && !role.equals("information"))throw error(403,"仅质控或信息角色可登记政策");String code=text(body,"code"),title=text(body,"title"),kind=choice(body,"kind","national_standard","interpretation","indicator_pack","reference"),published=date(body,"publishedOn"),effective=date(body,"effectiveOn"),note=text(body,"note");if(effective.compareTo(published)<0)throw error(400,"生效日期不得早于发布日期");JsonNode impactsNode=body.get("impacts");if(impactsNode==null || !impactsNode.isArray() || impactsNode.isEmpty())throw error(400,"impacts须为非空数组");List<String> impacts=new ArrayList<>();for(JsonNode x:impactsNode){if(!x.isTextual() || !Set.of("clause","indicator_def","code_set","selection_set","scoring_rule").contains(x.asText()))throw error(400,"影响类型必须为契约规定的枚举");impacts.add(x.asText());}
        return change(role,"登记政策元数据",code,s->{if(s.workspace.policies.stream().anyMatch(p->p.code.equals(code)))throw error(409,"政策编号已存在");Policy p=new Policy();p.id=id("policy");p.code=code;p.title=title;p.kind=kind;p.publishedOn=published;p.effectiveOn=effective;p.impacts=List.copyOf(impacts);p.note=note;p.status=impacts.contains("scoring_rule")?"blocked":"registered";s.workspace.policies.add(p);return p;});
    }
    public Policy confirmPolicy(String actor,String pid) {
        quality(actor);return change(actor,"确认政策登记",pid,s->{Policy p=find(s.workspace.policies,pid,x->x.id,"政策登记");if(p.impacts.contains("scoring_rule") || p.status.equals("blocked"))throw error(409,"涉及scoring_rule的登记已阻断，不允许确认或自动改变计分");if(p.status.equals("confirmed"))throw error(409,"该政策已确认");p.status="confirmed";return p;});
    }
    public Evidence upload(String actor,String type,String ownerId,MultipartFile file) throws IOException {
        quality(actor);if(!Set.of("prereq","improvement").contains(type))throw error(400,"附件归属须为prereq或improvement");if(file.isEmpty() || file.getSize()>5L*1024*1024)throw error(400,"附件不能为空且最大5MiB");String original=file.getOriginalFilename();if(original==null || original.isBlank())throw error(400,"附件缺少文件名");String name=original.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);if(name.length()>180 || name.indexOf('\0')>=0 || name.contains("\r") || name.contains("\n"))throw error(400,"附件文件名不合法");int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);if(!Set.of("pdf","png","jpg","jpeg","txt","docx").contains(ext))throw error(400,"仅允许pdf/png/jpg/jpeg/txt/docx附件");byte[] bytes=file.getBytes();validateContent(ext,bytes);String eid=id("file");Path path=uploads.resolve(eid).normalize();if(!path.startsWith(uploads))throw error(400,"附件路径不合法");String filename=name;
        synchronized(this){if(type.equals("prereq"))prereq(state,ownerId);else if(improvement(state,ownerId).status.equals("closed"))throw error(409,"已关闭改进单须先由质控重新打开才能补充附件");Files.write(path,bytes,StandardOpenOption.CREATE_NEW);try{return change(actor,"上传真实本地佐证",ownerId,s->{Evidence e=new Evidence();e.id=eid;e.ownerType=type;e.ownerId=ownerId;e.fileName=filename;e.size=bytes.length;e.createdAt=now();e.version=(int)s.workspace.evidenceFiles.stream().filter(x->x.ownerType.equals(type) && x.ownerId.equals(ownerId) && x.fileName.equals(filename)).count()+1;s.workspace.evidenceFiles.add(e);if(type.equals("prereq"))prereq(s,ownerId).evidenceCount++;else improvement(s,ownerId).evidenceCount++;return e;});}catch(RuntimeException e){Files.deleteIfExists(path);throw e;}}
    }
    private static void validateContent(String ext,byte[] bytes) throws IOException {
        boolean valid=switch(ext){case "pdf"->bytes.length>=5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");case "png"->bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});case "jpg","jpeg"->bytes.length>=3 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xd8 && bytes[2]==(byte)0xff;case "txt"->true;case "docx"->validDocx(bytes);default->false;};if(!valid)throw error(400,"文件内容与扩展名不一致");
    }
    private static boolean validDocx(byte[] bytes) throws IOException {boolean content=false,document=false;long expanded=0;try(var z=new ZipInputStream(new ByteArrayInputStream(bytes))){for(var e=z.getNextEntry();e!=null;e=z.getNextEntry()){if(e.getName().equals("[Content_Types].xml"))content=true;if(e.getName().equals("word/document.xml"))document=true;byte[] buffer=new byte[8192];for(int n;(n=z.read(buffer))!=-1;){expanded+=n;if(expanded>20L*1024*1024)throw error(400,"DOCX展开内容过大");}}}catch(java.util.zip.ZipException e){return false;}return content && document;}
    public record Download(String filename,String contentType,byte[] bytes) {}
    public synchronized Download download(String actor,String eid) throws IOException {
        String role=role(actor);if(role.equals("department"))throw error(403,"科室角色不可下载全院管理佐证");Evidence e=find(state.workspace.evidenceFiles,eid,x->x.id,"附件");Path path=uploads.resolve(e.id).normalize();if(!path.startsWith(uploads) || !Files.isRegularFile(path))throw error(404,"附件文件不存在");String ext=e.fileName.substring(e.fileName.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);String mime=switch(ext){case "pdf"->"application/pdf";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "txt"->"text/plain;charset=UTF-8";default->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";};return new Download(e.fileName,mime,Files.readAllBytes(path));
    }
    public synchronized Download report(String actor,String scoreId) throws IOException {
        if(role(actor).equals("department"))throw error(403,"科室角色不可下载全院试算报告");ReportSnapshot snapshot=state.reportSnapshots.get(scoreId);if(snapshot==null)throw error(404,"试算报告不存在");return new Download("demo-report-"+scoreId+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",DocxReport.render(snapshot));
    }
    public Object reset(String actor,JsonNode body) {
        quality(actor);if(!text(body,"confirm").equals("RESET_DEMO"))throw error(400,"重置需要confirm=RESET_DEMO");synchronized(this){State initial=SyntheticData.initial();log(initial,actor,"恢复初始演示数据","demo","仅重置合成业务状态；上传旧文件不再对外引用");repository.save(initial);state=initial;return Map.of("id","demo-reset");}
    }
}
