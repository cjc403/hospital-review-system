package cn.demo.review;

import cn.demo.review.domain.Scoring;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScoringTest {
    @Test void evenMedianIsMeanOfMiddleValues() {
        var result=Scoring.evaluate(List.of(2d,4d,9d,10d),"high","all_or_nothing",6);
        assertEquals(6.5,result.mergedValue()); assertEquals(true,result.conform());
    }
    @Test void scaleMustAlsoPassLastCalendarYear() {
        var result=Scoring.evaluate(List.of(3d,3d,3d,1d),"high","all_or_nothing",2);
        assertEquals(false,result.conform()); assertEquals("last_year_fail",result.reasonCode());
    }
    @Test void worseningUsesWorstYearAndTiedTrendUsesMedian() {
        assertEquals(0.04,Scoring.evaluate(List.of(0.01,0.02,0.04),"low","interval_trend",0.02).mergedValue());
        assertEquals(0.01,Scoring.evaluate(List.of(0.01,0.02,0.005),"low","interval_trend",0.02).mergedValue());
    }
    @Test void emptyValuesAreNotIncludedAndMissingFinalYearCannotPassScale() {
        assertNull(Scoring.evaluate(Arrays.asList(null,null),"high","all_or_nothing",2).conform());
        assertEquals(false,Scoring.evaluate(Arrays.asList(3d,3d,null),"high","all_or_nothing",2).conform());
    }
    @Test void gradesUseStrictTenPercentDeferralAndExactRatioBoundaries() {
        assertEquals("jia",Scoring.grade(9,10,false,0.1));
        assertEquals("yi",Scoring.grade(8,10,false,0));
        assertEquals("bing",Scoring.grade(7,10,false,0));
        assertEquals("deferred",Scoring.grade(9,10,false,0.10001));
        assertEquals("deferred",Scoring.grade(10,10,true,0));
        assertNull(Scoring.grade(0,0,false,0));
        assertNull(Scoring.grade(6,10,false,0));
    }
    @Test void relativeDeviationUsesHomeAsBaseAndHandlesZero() {
        assertEquals(0.1,Scoring.deviation(100,90),1e-9);
        assertEquals(0,Scoring.deviation(0,0)); assertEquals(1,Scoring.deviation(0,2));
    }
}
