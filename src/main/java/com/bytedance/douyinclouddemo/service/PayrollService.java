package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.config.AppProperties;
import com.bytedance.douyinclouddemo.model.PayrollSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PayrollService {
    public static final int BASE_PAY = 3000;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Map<String, Integer> GIFT_PAY = new LinkedHashMap<>();
    static {
        GIFT_PAY.put("coffee", 1);
        GIFT_PAY.put("keyboard", 5);
        GIFT_PAY.put("monitor", 3);
        GIFT_PAY.put("vacation", 10);
    }

    private final UsageLedgerService ledger;
    private final AppProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Month> months = new LinkedHashMap<>();
    private Path file;

    public PayrollService(UsageLedgerService ledger, AppProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        Path dir = Paths.get(properties.getDataDir());
        file = dir.resolve("payroll.jsonl");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        Map<String, Object> row = mapper.readValue(line, Map.class);
                        add(row);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[payroll] load failed: " + e.getMessage());
        }
    }

    public static Integer giftPay(String gift) {
        return GIFT_PAY.get(gift);
    }

    @SuppressWarnings("unchecked")
    private void add(Map<String, Object> row) {
        long at = row.get("at") instanceof Number ? ((Number) row.get("at")).longValue() : System.currentTimeMillis();
        String kind = String.valueOf(row.getOrDefault("kind", "note"));
        double amount = row.get("amount") instanceof Number ? ((Number) row.get("amount")).doubleValue() : 0;
        String detail = String.valueOf(row.getOrDefault("detail", ""));
        Month month = months.computeIfAbsent(monthKey(at), key -> new Month());
        if ("gift".equals(kind)) month.gifts += amount;
        else month.fines -= amount;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", at);
        entry.put("kind", kind);
        entry.put("amount", amount);
        entry.put("detail", detail);
        month.recent.add(0, entry);
        if (month.recent.size() > 20) month.recent.remove(month.recent.size() - 1);
    }

    public synchronized void record(String kind, double amount, String detail) {
        long at = System.currentTimeMillis();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", at);
        row.put("kind", kind);
        row.put("amount", amount);
        row.put("detail", detail);
        add(row);
        try {
            Files.write(file, (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[payroll] write failed: " + e.getMessage());
        }
    }

    public synchronized PayrollSummary summary() {
        String month = monthKey(System.currentTimeMillis());
        Month current = months.getOrDefault(month, new Month());
        Map<String, Object> usage = ledger.monthlyUsage(month);
        int tokens = usage.get("tokens") instanceof Number ? ((Number) usage.get("tokens")).intValue() : 0;
        double cost = usage.get("cost") instanceof Number ? ((Number) usage.get("cost")).doubleValue() : 0;
        PayrollSummary summary = new PayrollSummary();
        summary.setMonth(month);
        summary.setBase(BASE_PAY);
        summary.setGifts((int) current.gifts);
        summary.setFines((int) current.fines);
        summary.setTokens(tokens);
        summary.setCost(cost);
        summary.setActual(BASE_PAY + current.gifts - current.fines - cost);
        summary.setRecent(new ArrayList<>(current.recent));
        return summary;
    }

    private String monthKey(long at) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), SHANGHAI).toLocalDate().toString().substring(0, 7);
    }

    private static class Month {
        private double gifts;
        private double fines;
        private final List<Map<String, Object>> recent = new ArrayList<>();
    }
}
