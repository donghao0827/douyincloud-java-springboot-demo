package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.config.AppProperties;
import com.bytedance.douyinclouddemo.model.ChatResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 常驻内容工坊：朝九晚六，每次只推进一小步，把真实文件写进 workspace。
 * 云端默认关闭，控制台「立即干一步」可强制验证。
 */
@Service
public class BackgroundAgentService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> ALLOWED_EXT = new HashSet<>(Arrays.asList(
            ".html", ".css", ".js", ".mjs", ".md", ".json", ".svg", ".txt"));
    private static final int MAX_BYTES = 200 * 1024;

    private final AppProperties props;
    private final DeepSeekService deepSeek;
    private final AiGate gate;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "kapu-background");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Map<String, Object> status = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile boolean autoEnabled;
    private volatile int tickSeconds;
    private Path settingsFile;
    private volatile long lastTick = 0;
    private Path workspace;

    public BackgroundAgentService(AppProperties props, DeepSeekService deepSeek, AiGate gate) {
        this.props = props;
        this.deepSeek = deepSeek;
        this.gate = gate;
    }

    @PostConstruct
    public void init() {
        workspace = Paths.get(props.getWorkspace()).toAbsolutePath().normalize();
        autoEnabled = props.isBackgroundEnabled();
        tickSeconds = Math.max(60, props.getTickSeconds());
        settingsFile = Paths.get(props.getDataDir()).resolve("agent-settings.json");
        if (Files.exists(settingsFile)) {
            try {
                JsonNode saved = mapper.readTree(settingsFile.toFile());
                autoEnabled = saved.path("autoEnabled").asBoolean(autoEnabled);
                tickSeconds = Math.max(60,saved.path("tickSeconds").asInt(tickSeconds));
                gate.setPaused(saved.path("paused").asBoolean(gate.isPaused()));
            } catch (Exception e) {
                throw new IllegalStateException("无法读取主播设置", e);
            }
        }
        status.put("state", "off");
        status.put("working", false);
        status.put("dateKey", "");
        status.put("stepsToday", 0);
        status.put("totalSteps", 0);
        status.put("lastAt", null);
        status.put("lastSummary", "");
        status.put("lastNext", "");
        status.put("lastFile", "");
        status.put("lastError", null);
        try {
            ensureWorkspace();
            Map<String, Object> state = readState();
            status.put("totalSteps", number(state.get("totalSteps")));
            status.put("lastSummary", String.valueOf(state.getOrDefault("lastSummary", "")));
            status.put("lastNext", String.valueOf(state.getOrDefault("next", "")));
        } catch (Exception e) {
            status.put("lastError", e.getMessage());
        }
    }

    private void ensureWorkspace() throws Exception {
        Files.createDirectories(workspace.resolve("artifacts"));
        Files.createDirectories(workspace.resolve("journal"));
        Path brief = workspace.resolve("PROJECT.md");
        if (!Files.exists(brief)) {
            try (InputStream input = new ClassPathResource("background/PROJECT.md").getInputStream()) {
                Files.write(brief, StreamUtils.copyToString(input, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
            }
        }
        Path state = workspace.resolve("STATE.json");
        if (!Files.exists(state)) {
            Map<String, Object> initial = new LinkedHashMap<>();
            initial.put("project", "卡噗的办公室");
            initial.put("createdAt", System.currentTimeMillis());
            initial.put("day", "");
            initial.put("totalSteps", 0);
            initial.put("todaySteps", 0);
            initial.put("lastSummary", "");
            initial.put("next", "先做一版咖啡广告页的新配色，验证暖色绘本风");
            initial.put("milestones", new ArrayList<>());
            initial.put("lastAt", null);
            writeState(initial);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> map;
        synchronized (status) { map = new LinkedHashMap<>(status); }
        map.put("autoEnabled", autoEnabled);
        map.put("busy", busy.get());
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("start", props.getWorkStart());
        schedule.put("end", props.getWorkEnd());
        schedule.put("days", props.getWorkDays());
        schedule.put("tickSeconds", tickSeconds);
        schedule.put("workspace", workspace == null ? "" : workspace.toString());
        map.put("schedule", schedule);
        return map;
    }

    @Scheduled(fixedDelay = 60_000)
    public void scheduled() {
        if (!autoEnabled) return;
        if (gate.isPaused()) return;
        if (!isWorkTime()) return;
        long now = System.currentTimeMillis();
        if (now - lastTick < tickSeconds * 1000L) return;
        lastTick = now;
        executor.submit(() -> step(false));
    }

    public Map<String, Object> step(boolean force) {
        if (busy.get()) return skipped("busy");
        String skip = null;
        if (!force && !autoEnabled) skip = "disabled";
        if (!force && !isWorkTime()) skip = "off-duty";
        if (gate.isPaused()) skip = "ai-paused";
        if (deepSeek.config().get("hasKey") != Boolean.TRUE) skip = "no-key";
        if (skip != null) {
            status.put("state", "off");
            status.put("working", false);
            return skipped(skip);
        }

        if (!busy.compareAndSet(false,true)) return skipped("busy");
        lastTick = System.currentTimeMillis();
        status.put("working", true);
        try {
            Map<String, Object> state = readState();
            List<String> files = listFiles();
            List<Map<String, Object>> journal = recentJournal();
            String brief = readBrief();

            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("总步数", number(state.get("totalSteps")));
            progress.put("上次产出", state.getOrDefault("lastSummary", ""));
            progress.put("下一步计划", state.getOrDefault("next", ""));
            progress.put("里程碑", state.getOrDefault("milestones", new ArrayList<>()));
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("项目说明", brief);
            context.put("当前进度", progress);
            context.put("已有文件", files);
            context.put("最近日志", journal);
            context.put("今天已工作步数", number(state.get("todaySteps")));

            ChatResult result = deepSeek.chat("background", "上班产出", prompt(), toJson(context),
                    4000, 0.85, 30000, 1);
            if (!result.isOk()) {
                status.put("state", "error");
                status.put("lastError", result.getErrorType());
                return result(false, result.getErrorType());
            }
            JsonNode data = result.getData();
            String action = data == null ? "" : data.path("action").asText("");
            if (!"write_file".equals(action) && !"note".equals(action)) {
                status.put("state", "error");
                status.put("lastError", "invalid_output");
                return result(false, "invalid_output");
            }
            String summary = clean(data.path("summary").asText(""), 80);
            String next = clean(data.path("next").asText(""), 60);
            String file = "";
            if ("write_file".equals(action)) {
                String path = data.path("path").asText("");
                String content = data.path("content").asText("");
                Path target = resolveArtifact(path);
                if (target == null || content.trim().isEmpty()
                        || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                    status.put("state", "error");
                    status.put("lastError", "invalid_path_or_content");
                    return result(false, "invalid_path_or_content");
                }
                Files.createDirectories(target.getParent());
                Files.write(target, content.getBytes(StandardCharsets.UTF_8));
                file = workspace.relativize(target).toString().replace('\\', '/');
            }

            String dateKey = ZonedDateTime.now(SHANGHAI).toLocalDate().toString();
            appendJournal(dateKey, "write_file".equals(action)
                    ? "产出 " + file + "：" + summary : "只记一笔：" + summary);
            int total = number(state.get("totalSteps")) + 1;
            int today = number(state.get("todaySteps")) + 1;
            List<?> milestones = new ArrayList<>();
            if (state.get("milestones") instanceof List) milestones = (List<?>) state.get("milestones");
            List<Object> nextMilestones = new ArrayList<>(milestones);
            nextMilestones.add(file.isEmpty() ? "note" : file);
            if (nextMilestones.size() > 50) nextMilestones = nextMilestones.subList(nextMilestones.size() - 50, nextMilestones.size());

            Map<String, Object> updated = new LinkedHashMap<>(state);
            updated.put("day", dateKey);
            updated.put("totalSteps", total);
            updated.put("todaySteps", today);
            updated.put("lastSummary", summary);
            updated.put("next", next);
            updated.put("milestones", nextMilestones);
            updated.put("lastAt", System.currentTimeMillis());
            writeState(updated);

            status.put("state", "on");
            status.put("working", false);
            status.put("dateKey", dateKey);
            status.put("stepsToday", today);
            status.put("totalSteps", total);
            status.put("lastAt", System.currentTimeMillis());
            status.put("lastSummary", summary);
            status.put("lastNext", next);
            status.put("lastFile", file);
            status.put("lastError", null);
            return result(true, null, file);
        } catch (Exception e) {
            status.put("state", "error");
            status.put("working", false);
            status.put("lastError", e.getMessage());
            return result(false, e.getMessage());
        } finally {
            status.put("working", false);
            busy.set(false);
        }
    }

    public synchronized Map<String, Object> updateSettings(Boolean paused, Boolean enabled, Integer interval)
            throws java.io.IOException {
        boolean nextPaused = paused == null ? gate.isPaused() : paused;
        boolean nextEnabled = enabled == null ? autoEnabled : enabled;
        int nextInterval = interval == null ? tickSeconds : interval;
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("paused", nextPaused);
        values.put("autoEnabled", nextEnabled);
        values.put("tickSeconds", nextInterval);
        Files.createDirectories(settingsFile.getParent());
        Path temp = Files.createTempFile(settingsFile.getParent(), "agent-settings-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), values);
            Files.move(temp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        gate.setPaused(nextPaused);
        autoEnabled = nextEnabled;
        tickSeconds = nextInterval;
        Map<String,Object> response = result(true, null);
        response.putAll(values);
        response.put("status", status());
        return response;
    }

    private String prompt() {
        try (InputStream input = new ClassPathResource("prompts/background.txt").getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "你是内容工坊的常驻同事。只输出 JSON。";
        }
    }

    private boolean isWorkTime() {
        ZonedDateTime now = ZonedDateTime.now(SHANGHAI);
        int weekday = now.getDayOfWeek().getValue();
        String days = props.getWorkDays() == null ? "1,2,3,4,5,6,7" : props.getWorkDays();
        boolean allowed = false;
        for (String part : days.split(",")) {
            try {
                if (Integer.parseInt(part.trim()) == weekday) allowed = true;
            } catch (Exception ignored) {
            }
        }
        if (!allowed) return false;
        int start = parseMinutes(props.getWorkStart(), 9 * 60);
        int end = parseMinutes(props.getWorkEnd(), 18 * 60);
        int minute = now.getHour() * 60 + now.getMinute();
        if (start <= end) return minute >= start && minute < end;
        return minute >= start || minute < end;
    }

    private int parseMinutes(String value, int fallback) {
        if (value == null) return fallback;
        String[] parts = value.trim().split(":");
        if (parts.length != 2) return fallback;
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return fallback;
            return hour * 60 + minute;
        } catch (Exception e) {
            return fallback;
        }
    }

    private Path resolveArtifact(String relative) {
        if (relative == null || relative.trim().isEmpty() || relative.startsWith("/") || relative.contains("\0")) return null;
        Path path = workspace.resolve(relative).normalize();
        if (!path.startsWith(workspace)) return null;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !ALLOWED_EXT.contains(name.substring(dot).toLowerCase())) return null;
        return path;
    }

    private List<String> listFiles() throws Exception {
        List<String> result = new ArrayList<>();
        Files.walk(workspace, 3).filter(Files::isRegularFile).forEach(path -> {
            String relative = workspace.relativize(path).toString().replace('\\', '/');
            if (!relative.startsWith(".") && result.size() < 50) result.add(relative);
        });
        Collections.sort(result);
        return result;
    }

    private List<Map<String, Object>> recentJournal() throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        Path dir = workspace.resolve("journal");
        if (!Files.exists(dir)) return result;
        List<Path> files = new ArrayList<>();
        Files.list(dir).filter(path -> path.toString().endsWith(".md")).forEach(files::add);
        files.sort(Comparator.comparing(Path::getFileName).reversed());
        for (int i = 0; i < Math.min(3, files.size()); i++) {
            String text = new String(Files.readAllBytes(files.get(i)), StandardCharsets.UTF_8);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", files.get(i).getFileName().toString().replace(".md", ""));
            row.put("text", text.length() > 600 ? text.substring(text.length() - 600) : text);
            result.add(row);
        }
        return result;
    }

    private String readBrief() throws Exception {
        Path brief = workspace.resolve("PROJECT.md");
        if (!Files.exists(brief)) return "";
        String text = new String(Files.readAllBytes(brief), StandardCharsets.UTF_8);
        return text.length() > 900 ? text.substring(0, 900) : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readState() {
        Path state = workspace.resolve("STATE.json");
        try {
            if (Files.exists(state)) return mapper.readValue(Files.readAllBytes(state), Map.class);
        } catch (Exception ignored) {
        }
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("totalSteps", 0);
        initial.put("todaySteps", 0);
        initial.put("lastSummary", "");
        initial.put("next", "");
        initial.put("milestones", new ArrayList<>());
        return initial;
    }

    private void writeState(Map<String, Object> state) throws Exception {
        Files.write(workspace.resolve("STATE.json"), mapper.writeValueAsBytes(state));
    }

    private void appendJournal(String dateKey, String text) throws Exception {
        Path file = workspace.resolve("journal").resolve(dateKey + ".md");
        String stamp = ZonedDateTime.now(SHANGHAI).toLocalTime().withNano(0).toString();
        if (stamp.length() > 5) stamp = stamp.substring(0, 5);
        String existing = Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                : "# " + dateKey + " 工作日志\n\n";
        Files.write(file, (existing + "- " + stamp + " " + text + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String clean(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private Map<String, Object> result(boolean ok, String reason) {
        return result(ok, reason, null);
    }

    private Map<String, Object> result(boolean ok, String reason, String file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", ok);
        if (reason != null) map.put("error", reason);
        if (file != null) map.put("file", file);
        return map;
    }

    private Map<String, Object> skipped(String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("skipped", reason);
        return map;
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
