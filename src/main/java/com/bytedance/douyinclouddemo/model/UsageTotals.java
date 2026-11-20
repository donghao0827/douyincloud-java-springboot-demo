package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class UsageTotals {
    private int calls;
    private int failures;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private int cacheHitTokens;
    private int cacheMissTokens;
    private int reasoningTokens;
    private double costCny;
    private long latencyMs;

    public void add(boolean ok, ChatUsage usage, double cost, long latency) {
        if (ok) calls++; else failures++;
        promptTokens += usage.getPromptTokens();
        completionTokens += usage.getCompletionTokens();
        totalTokens += usage.getTotalTokens();
        cacheHitTokens += usage.getCacheHitTokens();
        cacheMissTokens += usage.getCacheMissTokens();
        reasoningTokens += usage.getReasoningTokens();
        costCny += cost;
        latencyMs += Math.max(0, latency);
    }

    public int getAvgLatencyMs() {
        return calls > 0 ? (int) (latencyMs / calls) : 0;
    }
}
