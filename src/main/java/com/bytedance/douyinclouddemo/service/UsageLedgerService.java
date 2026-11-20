package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.config.AppProperties;
import com.bytedance.douyinclouddemo.model.ChatUsage;
import com.bytedance.douyinclouddemo.model.CostResult;
import com.bytedance.douyinclouddemo.model.UsageTotals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UsageLedgerService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int RECENT_LIMIT = 20;

    private final PricingService pricing;
    private final AppProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UsageTotals allTime = new UsageTotals();
    private final UsageTotals session = new UsageTotals();
    private final Map<String, UsageTotals> byDay = new ConcurrentHashMap<>();
    private final Map<String, UsageTotals> byAgent = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> recent = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();
    private Path ledgerFile;

    public UsageLedgerService(PricingService pricing, AppProperties properties) {
        this.pricing = pricing;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(properties.getDataDir());
        ledgerFile = dir.resolve("usage.jsonl");
        try {
            Files.createDirectories(dir);
            if (Files.exists(ledgerFile)) {
                for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        Map<String, Object> row = mapper.readValue(line, Map.class);
                        addRowToTotals(row);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[usage] load failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void addRowToTotals(Map<String, Object> row) {
        UsageTotals totals = new UsageTotals();
        Map<String, Object> usageMap = (Map<String, Object>) row.getOrDefault("usage", new LinkedHashMap<>());
        ChatUsage usage = new ChatUsage();
        usage.setPromptTokens(number(usageMap.get("promptTokens")));
        usage.setCompletionTokens(number(usageMap.get("completionTokens")));
        usage.setTotalTokens(number(usageMap.get("totalTokens")));
        usage.setCacheHitTokens(number(usageMap.get("cacheHitTokens")));
        usage.setCacheMissTokens(number(usageMap.get("cacheMissTokens")));
        usage.setReasoningTokens(number(usageMap.get("reasoningTokens")));
        boolean ok = Boolean.TRUE.equals(row.get("ok"));
        double cost = numberDouble(row.get("costCny"));
        long latency = number(row.get("latencyMs"));
        totals.add(ok, usage, cost, latency);
        merge(allTime, totals);
        String day = dayKey(number(row.get("at")));
        byDay.computeIfAbsent(day, key -> new UsageTotals());
        merge(byDay.get(day), totals);
        String agent = String.valueOf(row.getOrDefault("agent", "unknown"));
        byAgent.computeIfAbsent(agent, key -> new UsageTotals());
        merge(byAgent.get(agent), totals);
    }

    private void merge(UsageTotals target, UsageTotals source) {
        target.setCalls(target.getCalls() + source.getCalls());
        target.setFailures(target.getFailures() + source.getFailures());
        target.setPromptTokens(target.getPromptTokens() + source.getPromptTokens());
        target.setCompletionTokens(target.getCompletionTokens() + source.getCompletionTokens());
        target.setTotalTokens(target.getTotalTokens() + source.getTotalTokens());
        target.setCacheHitTokens(target.getCacheHitTokens() + source.getCacheHitTokens());
        target.setCacheMissTokens(target.getCacheMissTokens() + source.getCacheMissTokens());
        target.setReasoningTokens(target.getReasoningTokens() + source.getReasoningTokens());
        target.setCostCny(target.getCostCny() + source.getCostCny());
        target.setLatencyMs(target.getLatencyMs() + source.getLatencyMs());
    }

    public synchronized Map<String, Object> record(String agent, String purpose, String model, boolean ok,
                                                   String errorType, Integer status, long latencyMs,
                                                   int attempt, ChatUsage usage) {
        long at = System.currentTimeMillis();
        CostResult cost = pricing.costOf(model, usage, at);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", java.util.UUID.randomUUID().toString());
        row.put("at", at);
        row.put("agent", agent);
        row.put("purpose", purpose == null ? "" : purpose);
        row.put("model", model);
        row.put("ok", ok);
        row.put("errorType", errorType);
        row.put("status", status);
        row.put("latencyMs", latencyMs);
        row.put("attempt", attempt);
        row.put("usage", usageMap(usage));
        row.put("priceBand", cost.getBand());
        row.put("costCny", cost.getCny());

        UsageTotals totals = new UsageTotals();
        totals.add(ok, usage, cost.getCny(), latencyMs);
        merge(allTime, totals);
        merge(session, totals);
        String day = dayKey(at);
        byDay.computeIfAbsent(day, key -> new UsageTotals());
        merge(byDay.get(day), totals);
        byAgent.computeIfAbsent(agent, key -> new UsageTotals());
        merge(byAgent.get(agent), totals);
        synchronized (recent) {
            recent.add(0, row);
            if (recent.size() > RECENT_LIMIT) recent.remove(recent.size() - 1);
        }
        try {
            Files.write(ledgerFile, (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[usage] write failed: " + e.getMessage());
        }
        return row;
    }

    private Map<String, Object> usageMap(ChatUsage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("promptTokens", usage.getPromptTokens());
        map.put("completionTokens", usage.getCompletionTokens());
        map.put("totalTokens", usage.getTotalTokens());
        map.put("cacheHitTokens", usage.getCacheHitTokens());
        map.put("cacheMissTokens", usage.getCacheMissTokens());
        map.put("reasoningTokens", usage.getReasoningTokens());
        return map;
    }

    public Map<String, Object> summary() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ledgerFile", ledgerFile == null ? "" : ledgerFile.toAbsolutePath().toString());
        map.put("startedAt", startedAt);
        map.put("sessionSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        map.put("session", totalsMap(session));
        map.put("today", totalsMap(byDay.getOrDefault(dayKey(System.currentTimeMillis()), new UsageTotals())));
        map.put("allTime", totalsMap(allTime));
        Map<String, Object> agents = new LinkedHashMap<>();
        byAgent.forEach((name, totals) -> agents.put(name, totalsMap(totals)));
        map.put("byAgent", agents);
        synchronized (recent) {
            map.put("recent", new ArrayList<>(recent));
        }
        return map;
    }

    public Map<String, Object> monthlyUsage(String month) {
        UsageTotals totals = new UsageTotals();
        byDay.forEach((day, value) -> {
            if (day.startsWith(month + "-")) merge(totals, value);
        });
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tokens", totals.getTotalTokens());
        map.put("cost", totals.getCostCny());
        return map;
    }

    private Map<String, Object> totalsMap(UsageTotals totals) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("calls", totals.getCalls());
        map.put("failures", totals.getFailures());
        map.put("promptTokens", totals.getPromptTokens());
        map.put("completionTokens", totals.getCompletionTokens());
        map.put("totalTokens", totals.getTotalTokens());
        map.put("cacheHitTokens", totals.getCacheHitTokens());
        map.put("cacheMissTokens", totals.getCacheMissTokens());
        map.put("reasoningTokens", totals.getReasoningTokens());
        map.put("costCny", Math.round(totals.getCostCny() * 1_000_000d) / 1_000_000d);
        map.put("avgLatencyMs", totals.getAvgLatencyMs());
        return map;
    }

    public String dayKey(long at) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), SHANGHAI).toLocalDate().toString();
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private double numberDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }
}
