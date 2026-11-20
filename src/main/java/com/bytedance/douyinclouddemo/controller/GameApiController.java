package com.bytedance.douyinclouddemo.controller;

import com.bytedance.douyinclouddemo.config.AppProperties;
import com.bytedance.douyinclouddemo.model.ActionEvent;
import com.bytedance.douyinclouddemo.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class GameApiController {
    private static final java.util.List<String> GIFT_KEYS = Arrays.asList("coffee", "keyboard", "monitor", "vacation");

    private final GameService game;
    private final RuntimeService runtime;
    private final SseService sse;
    private final DeepSeekService deepSeek;
    private final UsageLedgerService ledger;
    private final AiGate gate;
    private final AppProperties properties;
    private final BackgroundAgentService background;

    public GameApiController(GameService game, RuntimeService runtime, SseService sse,
                             DeepSeekService deepSeek, UsageLedgerService ledger, AiGate gate,
                             AppProperties properties, BackgroundAgentService background) {
        this.game = game;
        this.runtime = runtime;
        this.sse = sse;
        this.deepSeek = deepSeek;
        this.ledger = ledger;
        this.gate = gate;
        this.properties = properties;
        this.background = background;
    }

    @GetMapping("/api/state")
    public Map<String, Object> state() {
        return game.publicState();
    }

    @GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return sse.subscribe();
    }

    @PostMapping("/api/action")
    public ResponseEntity<Map<String, Object>> action(
            @RequestHeader(value = "X-Kapu-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!allowed(token)) return unauthorized();
        ActionEvent event = normalize(body);
        if (event == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("ok", false);
            error.put("error", "invalid event");
            return ResponseEntity.badRequest().body(error);
        }
        Map<String, Object> result = runtime.submit(event);
        result.put("ok", true);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    @PostMapping("/api/control")
    public ResponseEntity<Map<String, Object>> control(
            @RequestHeader(value = "X-Kapu-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!allowed(token)) return unauthorized();
        String action = String.valueOf(body.getOrDefault("action", ""));
        if ("start".equals(action)) action = "play";
        Map<String, Object> result = game.control(action);
        result.putIfAbsent("ok", true);
        sse.broadcast();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/usage")
    public Map<String, Object> usage() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("config", deepSeek.config());
        map.put("runtime", runtime.stats());
        map.put("usage", ledger.summary());
        return map;
    }

    @GetMapping("/api/usage/balance")
    public Map<String, Object> balance() {
        return deepSeek.balance();
    }

    @PostMapping("/api/ai/pause")
    public ResponseEntity<Map<String, Object>> pause(
            @RequestHeader(value = "X-Kapu-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        if (!allowed(token)) return unauthorized();
        boolean paused = Boolean.TRUE.equals(body.get("paused"));
        gate.setPaused(paused);
        sse.broadcast();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("paused", paused);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/agent/step")
    public Map<String, Object> agentStep(@RequestBody(required = false) Map<String, Object> body) {
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        Map<String, Object> result = background.step(force);
        result.put("status", background.status());
        sse.broadcast();
        return result;
    }

    @PostMapping("/api/agent/toggle")
    public Map<String, Object> agentToggle(@RequestBody(required = false) Map<String, Object> body) {
        boolean start = body != null && Boolean.TRUE.equals(body.get("start"));
        return background.toggle(start);
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("phase", game.getPhase());
        map.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return map;
    }

    private boolean allowed(String token) {
        String required = properties.getApiToken();
        return required == null || required.isEmpty() || required.equals(token);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", "unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(map);
    }

    @SuppressWarnings("unchecked")
    private ActionEvent normalize(Map<String, Object> body) {
        Object rawType = body.get("type");
        String type = rawType == null ? "" : String.valueOf(rawType);
        if (!Arrays.asList("join", "like", "gift", "comment").contains(type)) return null;
        ActionEvent event = new ActionEvent();
        event.setType(type);
        String user = body.get("user") == null ? "观众" : String.valueOf(body.get("user")).trim();
        if (user.isEmpty()) user = "观众";
        event.setUser(user.length() > 16 ? user.substring(0, 16) : user);
        event.setAt(System.currentTimeMillis());
        if (body.get("eventId") != null) event.setEventId(String.valueOf(body.get("eventId")));
        if ("comment".equals(type)) {
            String command = body.get("command") == null ? "" : String.valueOf(body.get("command")).trim();
            if (command.isEmpty()) return null;
            event.setCommand(command.length() > 120 ? command.substring(0, 120) : command);
        }
        if ("like".equals(type)) {
            int count = body.get("count") instanceof Number ? ((Number) body.get("count")).intValue() : 1;
            event.setCount(Math.max(1, Math.min(300, count)));
        }
        if ("gift".equals(type)) {
            String gift = body.get("gift") == null ? "coffee" : String.valueOf(body.get("gift"));
            event.setGift(GIFT_KEYS.contains(gift) ? gift : "coffee");
        }
        return event;
    }
}
