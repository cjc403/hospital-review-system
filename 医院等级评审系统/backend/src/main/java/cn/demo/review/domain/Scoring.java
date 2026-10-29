package cn.demo.review.domain;

import java.util.List;
import java.util.Objects;

/** Pure evaluation boundary. All sample results are trial calculations. */
public final class Scoring {
    private Scoring() {}
    public record Result(Double mergedValue, Boolean conform, String reasonCode) {}
    public static Result evaluate(List<Double> values, String direction, String scoreType, double target) {
        if (!List.of("high","low").contains(direction) || !List.of("all_or_nothing","interval_trend").contains(scoreType))
            throw new IllegalArgumentException("未知计分方向或类型");
        var present=values.stream().filter(Objects::nonNull).filter(Double::isFinite).toList();
        if(present.isEmpty()) return new Result(null,null,"not_applicable");
        var sorted=present.stream().sorted().toList();
        int n=sorted.size();
        double median=n%2==1?sorted.get(n/2):(sorted.get(n/2-1)+sorted.get(n/2))/2d;
        boolean high=direction.equals("high");
        if(scoreType.equals("all_or_nothing")) {
            if(!passes(median,high,target)) return new Result(median,false,"median_fail");
            Double last=values.get(values.size()-1);
            if(last==null || !Double.isFinite(last) || !passes(last,high,target)) return new Result(median,false,"last_year_fail");
            return new Result(median,true,"pass");
        }
        int better=0,worse=0;
        for(int i=1;i<present.size();i++) {
            int movement=Double.compare(present.get(i),present.get(i-1))*(high?1:-1);
            if(movement>0)better++; else if(movement<0)worse++;
        }
        double merged=worse>better?(high?sorted.get(0):sorted.get(n-1)):median;
        return new Result(merged,passes(merged,high,target),worse>better?"worst_year":"median");
    }
    private static boolean passes(double value,boolean high,double target) { return high?value>=target:value<=target; }
    public static String grade(int conform, int included, boolean nonCompliant, double errorRate) {
        if(nonCompliant || errorRate>0.1) return "deferred";
        if(included==0) return null;
        double ratio=(double)conform/included;
        return ratio>=0.9?"jia":ratio>=0.8?"yi":ratio>=0.7?"bing":null;
    }
    public static double deviation(double home, double hqms) { return home==0?(hqms==0?0:1):Math.abs(hqms-home)/Math.abs(home); }
}
