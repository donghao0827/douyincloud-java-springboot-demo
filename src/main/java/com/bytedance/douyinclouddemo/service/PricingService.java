package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.ChatUsage;
import com.bytedance.douyinclouddemo.model.CostResult;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class PricingService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Map<String, double[]> FLASH = price(0.04, 0.02, 2, 1, 8, 4);
    private static final Map<String, double[]> PRO = price(0.3, 0.15, 9, 4.5, 27, 13.5);

    private static Map<String, double[]> price(double hitPeak, double hitOff, double missPeak, double missOff, double outPeak, double outOff) {
        Map<String, double[]> map = new HashMap<>();
        map.put("hitPeak", new double[]{hitPeak});
        map.put("hitOff", new double[]{hitOff});
        map.put("missPeak", new double[]{missPeak});
        map.put("missOff", new double[]{missOff});
        map.put("outPeak", new double[]{outPeak});
        map.put("outOff", new double[]{outOff});
        return map;
    }

    public boolean isPeakTime(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(SHANGHAI);
        String date = String.format("%04d-%02d-%02d", local.getYear(), local.getMonthValue(), local.getDayOfMonth());
        String offpeak = System.getenv().getOrDefault("DS_OFFPEAK_DATES", "");
        Set<String> holidays = new HashSet<>(Arrays.asList(offpeak.split(",")));
        if (holidays.contains(date)) return false;
        DayOfWeek day = local.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int hour = local.getHour();
        return (hour >= 9 && hour < 12) || (hour >= 14 && hour < 18);
    }

    public CostResult costOf(String model, ChatUsage usage, long at) {
        ZonedDateTime time = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), SHANGHAI);
        boolean peak = isPeakTime(time);
        Map<String, double[]> table = "deepseek-v4-pro".equals(model) ? PRO : FLASH;
        double hit = peak ? table.get("hitPeak")[0] : table.get("hitOff")[0];
        double miss = peak ? table.get("missPeak")[0] : table.get("missOff")[0];
        double out = peak ? table.get("outPeak")[0] : table.get("outOff")[0];
        double cny = (usage.getCacheHitTokens() * hit + usage.getCacheMissTokens() * miss + usage.getCompletionTokens() * out) / 1_000_000d;
        return new CostResult(cny, peak ? "peak" : "off");
    }
}
