package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.ActionEvent;
import com.bytedance.douyinclouddemo.model.ChatResult;
import com.bytedance.douyinclouddemo.model.JudgeDecision;
import com.bytedance.douyinclouddemo.model.RouterDecision;
import com.bytedance.douyinclouddemo.model.TaskDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AgentService {
    private static final List<String> ROUTES = Arrays.asList("preset", "judge", "task", "ignore");
    private static final List<String> PRESETS = Arrays.asList("vote:1", "vote:2", "join", "like", "gift", "none");
    private static final List<String> TASKS = Arrays.asList("ad", "avatar", "game", "todo", "code", "none");
    private static final List<String> JUDGE_KINDS = Arrays.asList("vote", "risk", "action", "noop");
    private static final List<String> JUDGE_ACTIONS = Arrays.asList("play", "coffee", "peek", "hide", "none");
    private static final List<String> MOODS = Arrays.asList("calm", "tense", "celebrate");
    private static final List<Pattern> FORBIDDEN = Arrays.asList(
            Pattern.compile("<script[^>]+src\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<link\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<iframe\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<object\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<embed\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<base\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("@import\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("url\\s*\\(\\s*['\"]?\\s*(?:https?:|//)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:src|href|action)\\s*=\\s*[\"']?\\s*(?:https?:|//)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhttp-equiv\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:fetch|XMLHttpRequest|WebSocket|EventSource|sendBeacon)\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwindow\\s*\\.\\s*open\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:top|parent)\\s*\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdocument\\s*\\.\\s*cookie\\b", Pattern.CASE_INSENSITIVE)
    );

    private final AssetService assets;
    private final DeepSeekService deepSeek;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentService(AssetService assets, DeepSeekService deepSeek) {
        this.assets = assets;
        this.deepSeek = deepSeek;
    }

    public ChatResult router(ActionEvent event, Map<String, Object> snapshot) {
        return deepSeek.chat("router", "分诊", assets.prompt("router"), prompt(event, snapshot),
                100, 0.0, 8000, 1);
    }

    public ChatResult judge(ActionEvent event, Map<String, Object> snapshot) {
        return deepSeek.chat("judge", "判官裁决", assets.prompt("judge"), prompt(event, snapshot),
                220, 0.7, 8000, 1);
    }

    public ChatResult task(ActionEvent event, Map<String, Object> snapshot, String taskName) {
        return deepSeek.chat("task", "点单:" + taskName, assets.prompt("task"), prompt(event, snapshot),
                1800, 0.8, 20000, 1);
    }

    private String prompt(ActionEvent event, Map<String, Object> snapshot) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("user", clamp(event.getUser(), 16));
        view.put("type", event.getType());
        if (event.getCommand() != null) view.put("command", clamp(event.getCommand(), 60));
        if (event.getCount() != null) view.put("count", event.getCount());
        if (event.getGift() != null) view.put("gift", event.getGift());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("互动", view);
        root.put("局势", snapshot);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    public RouterDecision sanitizeRouter(JsonNode data) {
        if (data == null || !data.isObject()) return null;
        String route = data.path("route").asText("");
        if (!ROUTES.contains(route)) return null;
        RouterDecision decision = new RouterDecision();
        decision.setRoute(route);
        String preset = data.path("preset").asText("none");
        decision.setPreset(PRESETS.contains(preset) ? preset : "none");
        String task = data.path("task").asText("none");
        decision.setTask(TASKS.contains(task) ? task : "none");
        decision.setReason(clamp(data.path("reason").asText(""), 40));
        return decision;
    }

    public JudgeDecision sanitizeJudge(JsonNode data) {
        if (data == null || !data.isObject()) return null;
        String narration = clamp(data.path("narration").asText(""), 60);
        if (narration.isEmpty()) return null;
        JsonNode effect = data.path("effect");
        if (!effect.isObject()) effect = mapper.createObjectNode();
        String kind = effect.path("kind").asText("noop");
        if (!JUDGE_KINDS.contains(kind)) kind = "noop";
        Integer vote = null;
        if (effect.path("vote").asInt(0) == 1) vote = 1;
        if (effect.path("vote").asInt(0) == 2) vote = 2;
        int delta = clampInt(effect.path("delta").asInt(0), -20, 20);
        String action = effect.path("action").asText("none");
        if (!JUDGE_ACTIONS.contains(action)) action = "none";
        if ("vote".equals(kind) && vote == null) kind = "noop";
        if ("risk".equals(kind)) delta = clampInt(delta, -10, 10);
        if ("action".equals(kind) && "none".equals(action)) kind = "noop";
        if (!"vote".equals(kind)) vote = null;
        if (!"risk".equals(kind)) delta = 0;
        if (!"action".equals(kind)) action = "none";

        JudgeDecision decision = new JudgeDecision();
        decision.setNarration(narration);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        map.put("vote", vote);
        map.put("delta", delta);
        map.put("action", action);
        decision.setEffect(map);
        String mood = data.path("mood").asText("calm");
        decision.setMood(MOODS.contains(mood) ? mood : "calm");
        return decision;
    }

    public TaskDecision sanitizeTask(JsonNode data) {
        if (data == null || !data.isObject()) return null;
        String task = data.path("task").asText("");
        if (!TASKS.contains(task) || "none".equals(task)) return null;
        TaskDecision decision = new TaskDecision();
        decision.setTask(task);
        decision.setTitle(clamp(data.path("title").asText(""), 20));
        decision.setReply(clamp(data.path("reply").asText(""), 40));
        if ("avatar".equals(task)) {
            Map<String, Object> draw = sanitizeDraw(data.path("draw"));
            if (draw == null) return null;
            decision.setDraw(draw);
            return decision;
        }
        String html = data.path("html").asText("");
        if (!safeHtml(html)) return null;
        decision.setHtml(html);
        return decision;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeDraw(JsonNode draw) {
        if (draw == null || !draw.isObject()) return null;
        String bg = color(draw.path("bg").asText(""), "#fdf6e8");
        if (bg == null) bg = "#fdf6e8";
        JsonNode shapes = draw.path("shapes");
        if (!shapes.isArray() || shapes.size() == 0 || shapes.size() > 24) return null;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (JsonNode shape : shapes) {
            Map<String, Object> item = sanitizeShape(shape);
            if (item == null) return null;
            normalized.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bg", bg);
        result.put("shapes", normalized);
        return result;
    }

    private Map<String, Object> sanitizeShape(JsonNode shape) {
        if (shape == null || !shape.isObject()) return null;
        String type = shape.path("type").asText("");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        if ("circle".equals(type)) {
            Double cx = number(shape, "cx", 0, 200);
            Double cy = number(shape, "cy", 0, 200);
            Double r = number(shape, "r", 1, 200);
            String fill = color(shape.path("fill").asText(""), null);
            String stroke = color(shape.path("stroke").asText(""), null);
            if (cx == null || cy == null || r == null || (fill == null && stroke == null)) return null;
            item.put("cx", cx); item.put("cy", cy); item.put("r", r);
            if (fill != null) item.put("fill", fill);
            if (stroke != null) item.put("stroke", stroke);
            return item;
        }
        if ("rect".equals(type)) {
            Double x = number(shape, "x", 0, 200);
            Double y = number(shape, "y", 0, 200);
            Double w = number(shape, "w", 1, 200);
            Double h = number(shape, "h", 1, 200);
            Double rx = number(shape, "rx", 0, 100);
            String fill = color(shape.path("fill").asText(""), null);
            String stroke = color(shape.path("stroke").asText(""), null);
            if (x == null || y == null || w == null || h == null || (fill == null && stroke == null)) return null;
            item.put("x", x); item.put("y", y); item.put("w", w); item.put("h", h);
            if (rx != null) item.put("rx", rx);
            if (fill != null) item.put("fill", fill);
            if (stroke != null) item.put("stroke", stroke);
            return item;
        }
        if ("polygon".equals(type)) {
            JsonNode points = shape.path("points");
            if (!points.isArray() || points.size() < 3 || points.size() > 12) return null;
            List<List<Double>> list = new ArrayList<>();
            for (JsonNode point : points) {
                if (!point.isArray() || point.size() != 2) return null;
                Double px = clampNumber(point.get(0));
                Double py = clampNumber(point.get(1));
                if (px == null || py == null) return null;
                List<Double> pair = new ArrayList<>();
                pair.add(px); pair.add(py);
                list.add(pair);
            }
            String fill = color(shape.path("fill").asText(""), null);
            String stroke = color(shape.path("stroke").asText(""), null);
            if (fill == null && stroke == null) return null;
            item.put("points", list);
            if (fill != null) item.put("fill", fill);
            if (stroke != null) item.put("stroke", stroke);
            return item;
        }
        if ("line".equals(type)) {
            Double x1 = number(shape, "x1", 0, 200);
            Double y1 = number(shape, "y1", 0, 200);
            Double x2 = number(shape, "x2", 0, 200);
            Double y2 = number(shape, "y2", 0, 200);
            Double width = number(shape, "width", 1, 20);
            String stroke = color(shape.path("stroke").asText(""), "#2f4a34");
            if (x1 == null || y1 == null || x2 == null || y2 == null || stroke == null) return null;
            item.put("x1", x1); item.put("y1", y1); item.put("x2", x2); item.put("y2", y2);
            item.put("stroke", stroke); item.put("width", width == null ? 2 : width);
            return item;
        }
        if ("arc".equals(type)) {
            Double cx = number(shape, "cx", 0, 200);
            Double cy = number(shape, "cy", 0, 200);
            Double r = number(shape, "r", 1, 200);
            Double start = number(shape, "start", -7, 7);
            Double end = number(shape, "end", -7, 7);
            Double width = number(shape, "width", 1, 20);
            String stroke = color(shape.path("stroke").asText(""), "#2f4a34");
            if (cx == null || cy == null || r == null || start == null || end == null || stroke == null) return null;
            item.put("cx", cx); item.put("cy", cy); item.put("r", r);
            item.put("start", start); item.put("end", end);
            item.put("stroke", stroke); item.put("width", width == null ? 2 : width);
            return item;
        }
        return null;
    }

    private Double number(JsonNode node, String field, double min, double max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) return null;
        double number = value.asDouble();
        if (number < min || number > max) return null;
        return number;
    }

    private Double clampNumber(JsonNode value) {
        if (value == null || !value.isNumber()) return null;
        return Math.max(0, Math.min(200, value.asDouble()));
    }

    private String color(String value, String fallback) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return fallback;
        return value.toLowerCase();
    }

    public boolean safeHtml(String html) {
        if (html == null || html.trim().length() < 20) return false;
        if (html.getBytes(StandardCharsets.UTF_8).length > 20 * 1024) return false;
        if (!html.contains("<")) return false;
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(html).find()) return false;
        }
        return true;
    }

    private String clamp(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public List<String> taskKinds() {
        return new ArrayList<>(TASKS);
    }
}
