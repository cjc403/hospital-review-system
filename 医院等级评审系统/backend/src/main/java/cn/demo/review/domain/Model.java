package cn.demo.review.domain;

import java.util.*;

/** Mutable aggregate held only inside a serialized service transaction. No clinical data. */
public final class Model {
    private Model() {}
    public static class State {
        public int schemaVersion=1;
        public Workspace workspace=new Workspace();
        public Map<String,ReportSnapshot> reportSnapshots=new LinkedHashMap<>();
        public Map<String,String> improvementBaselinePeriods=new LinkedHashMap<>();
    }
    public static class Workspace {
        public Meta meta=new Meta();
        public List<Department> departments=new ArrayList<>();
        public List<Indicator> indicators=new ArrayList<>();
        public List<Batch> batches=new ArrayList<>();
        public List<Manual> manualValues=new ArrayList<>();
        public List<Reconcile> reconciliations=new ArrayList<>();
        public List<AuditSample> auditSamples=new ArrayList<>();
        public List<Prereq> prerequisites=new ArrayList<>();
        public List<Improvement> improvements=new ArrayList<>();
        public List<Policy> policies=new ArrayList<>();
        public List<ScoreRun> scoreRuns=new ArrayList<>();
        public List<Evidence> evidenceFiles=new ArrayList<>();
        public List<AuditLog> auditLogs=new ArrayList<>();
    }
    public static class Meta {
        public String hospitalName="江城市第一人民医院（虚构）", standard="三级医院评审标准（2025年版）样例演示", period="2026-09",dataAsOf="2026-09-30";
        public boolean demo=true;
        public int totalMonitoringClauses=203;
    }
    public static class Department { public String code,name,owner; public Department() {} public Department(String c,String n,String o){code=c;name=n;owner=o;} }
    public static class Indicator {
        public String id,code,name,chapter,dimension,owner,unit,direction,scoreType,annualAgg,collectMode;
        public double target,warn;
        public Double value,autoValue,numerator,denominator;
        public String light,source;
        public int definitionVersion=1;
        public String numeratorText,denominatorText,includeText,excludeText,batchId;
        public List<String> sourceTables=new ArrayList<>();
        public int excludedCount;
        public List<Trend> trends=new ArrayList<>();
        public List<Annual> annualValues=new ArrayList<>();
        public List<DepartmentValue> departmentValues=new ArrayList<>();
    }
    public static class Trend { public String period; public Double value; public Trend(){} public Trend(String p,Double v){period=p;value=v;} }
    public static class Annual { public int year; public Double value; public Annual(){} public Annual(int y,Double v){year=y;value=v;} }
    public static class DepartmentValue { public String code,name,light; public Double value,numerator,denominator; }
    public static class Batch { public String id,period,status,batchKind,createdAt,note; public int indicatorCount; }
    public static class Manual { public String id,indicatorId,indicatorName,deptCode,period,note,status,submitter,reviewer="",submittedAt; public double value; }
    public static class Reconcile { public String id,inpatientNo,dischargeDate,deptName,diffType,homeValue,hqmsValue,status,note; }
    public static class AuditSample { public String id,period,createdAt; public int sampleCount,errorCount; public double errorRate; public List<AuditItem> items=new ArrayList<>(); }
    public static class AuditItem { public String indicatorId,name,result; public Double homeValue,hqmsValue,deviation; }
    public static class Prereq { public String id,code,section,title,compliance,workflow,owner,statement; public boolean systemGenerated; public int evidenceCount; }
    public static class Improvement { public String id,indicatorId,indicatorName,origin,status,owner,dueDate,actionText="",closeMode="",closeNote=""; public double baseline,target; public Double latestValue; public int evidenceCount; }
    public static class Policy { public String id,code,title,kind,status,publishedOn,effectiveOn,note; public int version=1; public List<String> impacts=new ArrayList<>(); }
    public static class ScoreRun {
        public String id,grade,createdAt;
        public int cycleStart,cycleEnd,yearCount,conformCount,includedCount;
        public boolean trial=true,adopted=false;
        public double ratio;
        public List<String> warnings=new ArrayList<>();
        public List<ScoreItem> items=new ArrayList<>();
    }
    public static class ScoreItem { public String indicatorId,name,unit,reasonCode; public List<Annual> yearValues=new ArrayList<>(); public Double mergedValue; public Boolean conform; }
    public static class Evidence { public String id,ownerType,ownerId,fileName,createdAt; public long size; public int version; }
    public static class AuditLog { public String id,actor,action,objectId,detail,createdAt; }
    public static class ReportSnapshot {
        public Meta meta;
        public ScoreRun scoreRun;
        public List<Prereq> prerequisites=new ArrayList<>();
        public List<AuditSample> auditSamples=new ArrayList<>();
    }
    public record Member(String inpatientNo,String dischargeDate,String deptCode,String deptName,String sex,int age,String mainDiagnosis,String memberRole) {}
    public record MemberPage(List<Member> items,int total,int page,int size) {}
}
