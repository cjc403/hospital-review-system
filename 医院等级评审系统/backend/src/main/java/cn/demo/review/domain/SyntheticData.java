package cn.demo.review.domain;

import java.time.YearMonth;
import java.util.*;
import static cn.demo.review.domain.Model.*;

/** Fixed, fully synthetic facts. Patients are generated from these exact integer counts. */
public final class SyntheticData {
    private SyntheticData() {}
    public static final Set<String> PATIENT_INDICATORS=Set.of("i01","i02","i03","i04","i05","i06","i07","i08","i09","i10");
    private static final String[] NAMES={"手术患者院内死亡率","住院患者抗菌药物使用率","非计划重返手术室率","手术并发症发生率","医院感染发生率","VTE规范预防率","危急值及时处置率","核心制度落实率","门诊处方点评合格率","ECMO治疗成功撤机率（演示）","病例组合指数（CMI）","开放床位数","护士与医师人数比","平均住院日"};
    private static final String[] CHAPTERS={
        "医疗服务能力与医院质量安全","医疗服务能力与医院质量安全","医疗服务能力与医院质量安全","医疗服务能力与医院质量安全",
        "重点专业质量控制","单病种（术种）质量控制","重点专业质量控制","医疗服务能力与医院质量安全","重点专业质量控制",
        "重点医疗技术临床应用质量控制","医疗服务能力与医院质量安全","资源配置与运行","资源配置与运行","资源配置与运行"
    };
    private record Definition(String numerator,String denominator,String include,String exclude) {}
    private static final String DEMO_DEFINITION_NOTE=" 本指标目标与告警线仅为演示目标，非官方阈值；全部事实为合成样本，不接真实数据。";
    private static final Definition[] DEFINITIONS={
        new Definition(
            "统计期内接受手术且在本次住院死亡的合成出院患者数；同一住院只计1例。",
            "同期接受手术的合成出院患者数；院内死亡率=分子÷分母×100%。",
            "按出院日期归期；纳入具有已完成手术标识及明确住院结局的合成住院记录。",
            "排除取消手术、门诊手术、缺少出院结局和重复住院标识的记录。"),
        new Definition(
            "住院期间至少使用1次全身抗菌药物的合成出院患者数；多次用药只计1例。",
            "同一期合成出院患者总数；抗菌药物使用率=分子÷分母×100%。",
            "按出院日期归期；演示药品目录标识为全身抗菌药物的用药事件计入。",
            "排除仅局部外用药物、已撤销医嘱、门诊用药和重复出院记录。"),
        new Definition(
            "本次住院首次手术后发生非计划再次进入手术室的合成患者数。",
            "同期接受手术的合成出院患者数；非计划重返手术室率=分子÷分母×100%。",
            "同一住院内核对首次与再次手术时间，并要求再次手术的plannedReturn标识明确为false。",
            "排除计划分期手术、再次手术时间早于首次手术、计划标识缺失及重复手术事件。"),
        new Definition(
            "术后新发且被合成事件标识为手术相关并发症的出院患者数；同次住院只计1例。",
            "同期接受手术的合成出院患者数；手术并发症发生率=分子÷分母×100%。",
            "按出院日期归期；合成并发症发生时间须晚于本次手术开始时间。",
            "排除入院时已存在的同类病变、未完成手术、无时间先后信息及重复并发症记录。"),
        new Definition(
            "住院期间新发生且hospitalAcquired标识为true的医院感染合成病例数。",
            "同期合成出院患者总数；医院感染发生率=分子÷分母×100%。",
            "按出院日期归期；以合成感染事件与住院记录关联，每次住院有多次感染仍计1例。",
            "排除入院时已存在感染、仅定植且未认定感染、结论撤销和重复感染事件。"),
        new Definition(
            "完成VTE风险评估且按演示规则落实适宜药物或机械预防的合成目标术种病例数。",
            "需要VTE预防且无相应禁忌证的合成目标术种病例数；规范预防率=分子÷分母×100%。",
            "单病种（术种）样例限定为髋/膝关节置换及髋部骨折手术；按出院日期归期并核对预防完成标识。",
            "排除低风险无需预防、所有适宜预防方式均有禁忌证、术种不符及缺少风险评估的记录。"),
        new Definition(
            "首次有效危急值通知后30分钟内完成处置记录的合成住院病例数。",
            "具有有效危急值通知的合成住院病例数；及时处置率=分子÷分母×100%。",
            "每次住院选首条有效通知，核对通知与处置时间；30分钟仅为本演示的流程时限。",
            "排除复测后撤销的危急值、未成功送达通知、缺少时间戳及同次住院重复通知。"),
        new Definition(
            "适用的核心制度检查项目全部记录为已落实的合成抽检住院病例数。",
            "同一期完成适用项目检查的合成住院病例数；核心制度落实率=分子÷分母×100%。",
            "演示抽检覆盖交接班、查对及疑难病例讨论等适用项目；人工填报须经质控审核才覆盖显示值。",
            "排除未完成检查、所有项目均不适用、检查结论撤销及重复抽检的病例记录。"),
        new Definition(
            "最终点评结论为合格的合成门诊处方记录数；每张处方只计1次。",
            "同一期完成点评的合成门诊处方总数；处方点评合格率=分子÷分母×100%。",
            "按点评完成日期归期；使用处方适应证、剂量和用法检查结果形成合成最终结论。",
            "排除未点评处方、作废处方、点评结论撤销、缺少处方标识及重复点评记录。"),
        new Definition(
            "达到合成成功撤机标识的ECMO治疗疗程数；演示标识要求撤机后48小时内无需再次置管。",
            "同期结束ECMO支持且具有完整撤机结局的合成疗程数；成功撤机率=分子÷分母×100%。",
            "按ECMO支持结束日期归期，每个合成疗程计1次；数量仅供技术质控流程演示，不代表真实技术开展或临床规模。",
            "排除仍在ECMO支持、转院后结局不明、撤机后观察窗不足48小时及重复疗程记录。"),
        new Definition(
            "固定演示DRG权重版本下，各合成DRG组权重×该组出院病例数的加权总和。",
            "纳入合成DRG分组的出院病例总数；CMI=各组权重与病例数乘积之和÷病例总数。",
            "同一年度使用同一套固定合成分组与权重，按drg_formula读取预置汇总值；本演示不执行真实DRG分组。",
            "排除未分组、权重缺失、分组版本不一致及重复出院记录；不以ICD低风险病种替代DRG分组。"),
        new Definition(
            "年末12月31日实际可收治住院患者的开放床位数；单位为张，来自合成年末快照。",
            "不适用；开放床位数=年末同一统计时点的实际开放床位计数，不计算分子分母比例。",
            "统计可用于收治住院患者的普通病床和监护病床；年度值采用year_end_snapshot，同一张床只计1次。",
            "排除急诊留观床、日间治疗床、临时加床和已停用床位；不得把12个月床位数累加。"),
        new Definition(
            "年末同一统计时点在岗注册护士人数；来自合成人员快照，按人员标识去重。",
            "同一时点在岗执业（助理）医师人数；护士与医师人数比=护士人数÷医师人数。",
            "护士和医师均采用同一组织范围、同一时点的year_end_snapshot；演示比值不代表真实人员配备结论。",
            "排除未在岗、短期进修、资格无效和重复任职记录；不得将各月人员数直接相加。"),
        new Definition(
            "统计期内合成出院患者实际占用总床日；出院日期减入院日期，同日入出院按1日计。",
            "同期合成出院患者人次数，包含死亡病例；平均住院日=实际占用总床日÷出院患者人次数。",
            "按出院日期归期并完整计算跨月住院床日；演示年度month_avg采用12个月指标值均值。",
            "排除负住院日、入出院时间缺失、门诊及急诊留观和重复住院记录；分母为0时不计算。")
    };
    private static final double[] TARGET={0.3,40,0.4,1.5,1,90,95,95,95,90,1.2,800,1.8,7.5};
    private static final double[] WARN={0.4,55,0.7,2.5,1.5,80,90,90,90,85,1,700,1.5,8.5};
    private static final double[] CURRENT={0.5,58,0.25,2.1,0.8,87,96.4,94,98,92,1.18,850,1.7,8.1};
    public static State initial() {
        State s=new State(); Workspace w=s.workspace;
        w.departments.addAll(List.of(new Department("CARD","心血管内科","陈明（演示）"),new Department("SURG","普通外科","林晓（演示）"),new Department("NEUR","神经内科","张宁（演示）"),new Department("ORTH","骨科","刘芳（演示）"),new Department("RESP","呼吸内科","王磊（演示）"),new Department("GYNE","妇产科","赵悦（演示）")));
        for(int n=0;n<NAMES.length;n++) {
            Indicator i=new Indicator(); i.id="i%02d".formatted(n+1);i.code="DEMO-%02d".formatted(n+1);i.name=NAMES[n];i.chapter=CHAPTERS[n];i.dimension=n<7 || n==9?"质量安全":"服务效能";i.owner=n<7?"医务部":"质量管理办公室";i.unit=n<10?"%":n==11?"张":n==13?"天":"";i.direction=(n<5 || n==13)?"low":"high";i.scoreType=n==11 || n==12?"all_or_nothing":"interval_trend";i.annualAgg=n==10?"drg_formula":n==11 || n==12?"year_end_snapshot":"month_avg";i.collectMode=n==7?"manual":"auto";i.target=TARGET[n];i.warn=WARN[n];Definition definition=DEFINITIONS[n];i.numeratorText=definition.numerator();i.denominatorText=definition.denominator();i.includeText=definition.include()+DEMO_DEFINITION_NOTE;i.excludeText=definition.exclude();i.sourceTables=List.of(n<10?"demo_case_fact":"demo_summary_fact");i.excludedCount=3+n;i.batchId="b-initial";i.source="auto";
            if(n<10)i.sourceTables=List.of("visit_discharge","demo_case_fact");
            for(int y=2023;y<=2026;y++) {
                double factor=i.direction.equals("low")?(0.76+0.08*(y-2023)):(1.05-0.016*(y-2023));
                double value=n==11?new double[]{810,830,860,850}[y-2023]:n==12?new double[]{1.9,1.85,1.8,1.7}[y-2023]:round(CURRENT[n]*factor);
                i.annualValues.add(new Annual(y,n<10?Math.min(100,value):value));
            }
            for(int m=0;m<12;m++) {YearMonth p=YearMonth.of(2025,10).plusMonths(m);double f=i.direction.equals("low")?0.86+m*0.014:1.07-m*0.006;double v=m==11?CURRENT[n]:round(CURRENT[n]*f);i.trends.add(new Trend(p.toString(),n<10?Math.min(100,v):v));}
            fillFacts(i,w.departments,"2026-09");w.indicators.add(i);
        }
        Batch b=new Batch();b.id="b-initial";b.period="2026-09";b.status="success";b.batchKind="auto";b.indicatorCount=14;b.createdAt="2026-10-01T08:30:00+08:00";b.note="初始合成数据月结；非真实医院数据";w.batches.add(b);
        Manual m=new Manual();m.id="m-initial";m.indicatorId="i08";m.indicatorName=NAMES[7];m.deptCode="ALL";m.period="2026-09";m.value=96;m.note="合成核心制度检查补录，待质控审核";m.status="submitted";m.submitter="演示填报员";m.submittedAt="2026-10-02T09:00:00+08:00";w.manualValues.add(m);
        String[] differences={"主要诊断编码不一致","出院时间不一致","手术编码缺失","费用分类差异","入院病情差异","住院天数差异"};
        for(int n=0;n<12;n++){Reconcile r=new Reconcile();r.id="r%02d".formatted(n+1);r.inpatientNo="DEMO****%04d".formatted(1001+n);r.dischargeDate="2026-09-%02d".formatted(10+n);r.deptName=w.departments.get(n%6).name;r.diffType=differences[n%6];r.homeValue=n%2==0?"示例A":"2026-09-15";r.hqmsValue=n%2==0?"示例B":"2026-09-16";r.status="pending";r.note="病案差异不参与条款抽查错误率";w.reconciliations.add(r);}
        String[] titles={"床位规模与科室设置复核","诊疗科目与许可范围","重点科室设置与能力","医疗机构依法执业","医师执业资格管理","护士执业资格管理","医疗技术临床应用管理","药事服务与用药管理","医疗器械规范使用","临床检验质量管理","临床用血规范管理","病历书写与管理","核心制度落实","患者隐私保护","知情同意管理","医疗服务价格公示","公益性责任落实","对口支援与帮扶","突发事件医疗救援","医德医风管理","行业作风管理","投诉与纠纷处理","安全生产责任","消防安全管理","院感事件防控","重大医疗事件管理","信息与数据安全","应急预案与演练","重大风险整改复核"};
        for(int n=0;n<29;n++){Prereq p=new Prereq();p.id="p%02d".formatted(n+1);p.code="PRE-%02d".formatted(n+1);p.section=n<3?"床位规模与科室设置":n<16?"依法执业与规范服务":n<22?"公益性责任与行风诚信":"安全管理与重大事件";p.title=titles[n]+"（演示摘要）";p.compliance=n==25 || n==26?"unknown":"compliant";p.workflow=p.compliance.equals("unknown")?"draft":"accepted";p.owner=n<16?"医务部":"质量管理办公室";p.statement=p.compliance.equals("unknown")?"尚待演示复核":"基于合成资料的演示结论；不代表真实法律认定";w.prerequisites.add(p);}
        Prereq truth=new Prereq();truth.id="p30";truth.code="SYS-TRUTH";truth.section="系统数据真实性";truth.title="条款抽查数据真实性（系统生成）";truth.compliance="unknown";truth.workflow="draft";truth.owner="系统";truth.statement="尚未发起条款抽查";truth.systemGenerated=true;w.prerequisites.add(truth);
        for(int n=0;n<2;n++){Improvement im=new Improvement();im.id="im-initial-"+(n+1);Indicator i=w.indicators.get(n==0?3:12);im.indicatorId=i.id;im.indicatorName=i.name;im.origin=n==0?"red_light":"worst_year";im.status=n==0?"opened":"doing";im.owner=n==0?"普通外科":"护理部";im.dueDate="2026-11-30";im.baseline=i.value;im.target=i.target;im.latestValue=i.value;im.actionText=n==0?"":"排班与岗位能力提升（合成示例）";w.improvements.add(im);s.improvementBaselinePeriods.put(im.id,"2026-09");}
        String[] policyTitles={"三级医院评审标准（2025年版）","国家三级公立医院绩效监测操作手册（2025版）","院内演示指标口径说明"};
        for(int n=0;n<3;n++){Policy p=new Policy();p.id="policy-"+(n+1);p.code="POL-DEMO-"+(n+1);p.title=policyTitles[n];p.kind=n==2?"indicator_pack":"national_standard";p.status=n==2?"registered":"confirmed";p.publishedOn="2025-06-10";p.effectiveOn="2025-07-01";p.impacts=List.of(n==2?"indicator_def":"clause");p.note="本轮仅登记元数据；不自动解析或改写政策";w.policies.add(p);}
        return s;
    }
    public static void fillFacts(Indicator i,List<Department> departments,String period) {
        int n=Integer.parseInt(i.id.substring(1))-1;
        boolean later=YearMonth.parse(period).isAfter(YearMonth.of(2026,9));
        double desired=CURRENT[n]*(later?(i.direction.equals("low")?0.5:1.05):1);
        i.departmentValues.clear();
        double numerator=0,denominator=0;
        for(int d=0;d<departments.size();d++) {
            Department dep=departments.get(d); DepartmentValue v=new DepartmentValue();v.code=dep.code;v.name=dep.name;
            if(PATIENT_INDICATORS.contains(i.id)) {
                int denom=n<5?2000:500;
                int count=(int)Math.round(denom*desired/100d)+(d==0?1:d==1?-1:0);
                count=Math.max(0,Math.min(denom,count));v.denominator=(double)denom;v.numerator=(double)count;v.value=round(100d*count/denom);numerator+=count;denominator+=denom;
            } else {v.numerator=null;v.denominator=null;v.value=round(desired*(0.95+d*0.02));}
            v.light=light(i,v.value);i.departmentValues.add(v);
        }
        if(PATIENT_INDICATORS.contains(i.id)){i.numerator=numerator;i.denominator=denominator;i.autoValue=round(100d*numerator/denominator);}
        else{i.numerator=null;i.denominator=null;i.autoValue=round(desired);}
        i.value=i.autoValue;i.source="auto";i.light=currentLight(i,period);
    }
    public static String currentLight(Indicator i,String period) {
        String threshold=light(i,i.value);
        if(threshold.equals("na"))return threshold;
        var previous=i.trends.stream().filter(t->t.period.compareTo(period)<0).sorted(Comparator.comparing(t->t.period)).toList();
        if(previous.size()<2)return threshold;
        Trend one=previous.get(previous.size()-2),two=previous.get(previous.size()-1);
        YearMonth current=YearMonth.parse(period);
        if(!one.period.equals(current.minusMonths(2).toString()) || !two.period.equals(current.minusMonths(1).toString()) || one.value==null || two.value==null)return threshold;
        boolean worsening=i.direction.equals("high")?two.value<one.value && i.value<two.value:two.value>one.value && i.value>two.value;
        return worsening?"red":threshold;
    }
    public static String light(Indicator i,Double value) {
        if(value==null || !Double.isFinite(value))return "na";
        return i.direction.equals("high")?(value>=i.target?"green":value>=i.warn?"yellow":"red"):(value<=i.target?"green":value<=i.warn?"yellow":"red");
    }
    public static double round(double value){return Math.round(value*10000d)/10000d;}
}
