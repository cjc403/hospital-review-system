package cn.demo.review;

import cn.demo.review.demo.DemoScenarioCatalog;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DemoScenarioCatalogTest {
    @Test
    void fixedSeedProducesStablePeriodsValuesAndScenarioCoverage() {
        DemoScenarioCatalog first = new DemoScenarioCatalog();
        DemoScenarioCatalog second = new DemoScenarioCatalog();

        assertEquals("group-demo-2026", first.scenarioCode());
        assertEquals(12, first.periods().size());
        assertEquals(YearMonth.of(2025, 10), first.periods().get(0));
        assertEquals(YearMonth.of(2026, 9), first.periods().get(11));
        assertEquals(14, first.metricCodes().size());
        assertEquals(6, first.departmentCodes().size());
        assertEquals(first.value("i01", "SURG", first.periods().get(4)),
                second.value("i01", "SURG", second.periods().get(4)));
        assertTrue(first.value("i01", "SURG", first.periods().get(11))
                > first.value("i01", "SURG", first.periods().get(0)));
        assertTrue(first.value("i08", "CARD", first.periods().get(11))
                > first.value("i08", "CARD", first.periods().get(0)));
        assertEquals(Set.of("baseline", "worsening", "jump", "missing", "quality_gate", "recovery"),
                first.scenarioKinds());
    }

    @Test
    void generatedStatsExposeMinimumNarrativeCounters() {
        DemoScenarioCatalog catalog = new DemoScenarioCatalog();
        var stats = catalog.minimumStats();
        assertTrue(stats.periods() >= 12);
        assertTrue(stats.metrics() >= 14);
        assertTrue(stats.departments() >= 6);
        assertTrue(stats.appliedBatches() >= 12);
        assertTrue(stats.rejectedBatches() >= 4);
        assertTrue(stats.alertKinds() >= 5);
    }
}
