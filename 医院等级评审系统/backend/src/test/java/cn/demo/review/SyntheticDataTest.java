package cn.demo.review;

import cn.demo.review.domain.SyntheticData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticDataTest {
    @Test void metadataCoversFiveNationalChaptersWithIndicatorSpecificDefinitions() {
        var indicators=SyntheticData.initial().workspace.indicators;
        var chapters=Set.of("资源配置与运行","医疗服务能力与医院质量安全","重点专业质量控制",
                "单病种（术种）质量控制","重点医疗技术临床应用质量控制");
        String[][] expectedTerms={
                {"死亡","手术"},{"抗菌药物","出院"},{"非计划","手术"},{"并发症","手术"},
                {"医院感染","出院"},{"预防","目标术种"},{"处置","危急值"},{"核心制度","检查"},
                {"合格","处方"},{"撤机","ECMO"},{"权重","DRG"},{"床位","不适用"},
                {"护士","医师"},{"床日","出院"}
        };
        assertAll(
                ()->assertEquals(14,indicators.size()),
                ()->assertEquals(chapters,indicators.stream().map(i->i.chapter).collect(Collectors.toSet())),
                ()->{
                    for(int n=0;n<indicators.size();n++) {
                        var i=indicators.get(n);
                        assertTrue(i.numeratorText.contains(expectedTerms[n][0]),i.id+" lacks a specific numerator");
                        assertTrue(i.denominatorText.contains(expectedTerms[n][1]),i.id+" lacks a specific denominator/formula");
                        assertFalse(i.includeText.isBlank(),i.id+" lacks inclusion rules");
                        assertFalse(i.excludeText.isBlank(),i.id+" lacks exclusion rules");
                        assertTrue(i.includeText.contains("非官方阈值"),i.id+" must identify targets as demonstration only");
                        assertTrue(i.includeText.contains("合成") && i.includeText.contains("不接真实数据"));
                    }
                },
                ()->{
                    for(var field:List.of("numerator","denominator","include","exclude")) {
                        long distinct=indicators.stream().map(i->switch(field){case "numerator"->i.numeratorText;case "denominator"->i.denominatorText;case "include"->i.includeText;default->i.excludeText;}).distinct().count();
                        assertEquals(14,distinct,field+" definitions must be specific to each indicator");
                    }
                },
                ()->{
                    var vte=indicators.get(5);assertEquals("单病种（术种）质量控制",vte.chapter);
                    var ecmo=indicators.get(9);assertEquals("ECMO治疗成功撤机率（演示）",ecmo.name);
                    assertEquals("重点医疗技术临床应用质量控制",ecmo.chapter);
                    assertEquals("high",ecmo.direction);assertEquals("%",ecmo.unit);
                }
        );
    }
}
