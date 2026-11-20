package com.bytedance.douyinclouddemo;

import com.bytedance.douyinclouddemo.model.ChatUsage;
import com.bytedance.douyinclouddemo.model.CostResult;
import com.bytedance.douyinclouddemo.service.PricingService;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PricingServiceTest {
    private final PricingService pricing = new PricingService();

    @Test
    void peakAndOffpeakCost() {
        ChatUsage usage = new ChatUsage();
        usage.setCacheHitTokens(1_000_000);
        usage.setCacheMissTokens(1_000_000);
        usage.setCompletionTokens(1_000_000);
        ZonedDateTime peak = ZonedDateTime.parse("2026-11-19T10:00:00+08:00");
        CostResult peakCost = pricing.costOf("deepseek-flash", usage, peak.toInstant().toEpochMilli());
        assertEquals("peak", peakCost.getBand());
        assertEquals(0.04 + 2 + 8, peakCost.getCny(), 0.000001);

        ZonedDateTime off = ZonedDateTime.parse("2026-11-19T22:00:00+08:00");
        CostResult offCost = pricing.costOf("deepseek-flash", usage, off.toInstant().toEpochMilli());
        assertEquals("off", offCost.getBand());
        assertEquals(0.02 + 1 + 4, offCost.getCny(), 0.000001);
    }
}
