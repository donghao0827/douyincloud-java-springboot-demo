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
    private final ScreenRenderService screenRender;

    public GameApiController(GameService game, RuntimeService runtime, SseService sse,
                             DeepSeekService deepSeek, UsageLedgerService ledger, AiGate gate,
                             AppProperties properties, BackgroundAgentService background,
                             ScreenRenderService screenRender) {
        this.game = game;
        this.runtime = runtime;
        this.sse = sse;
        this.deepSeek = deepSeek;
        this.ledger = ledger;
        this.gate = gate;
        this.properties = properties;
        this.background = background;
        this.screenRender = screenRender;
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
        if (!operatorAllowed(token)) return unauthorized();
        if (!(body.get("paused") instanceof Boolean)) return ResponseEntity.badRequest().build();
        return saveSettings((Boolean) body.get("paused"), null, null);
    }

    @PostMapping("/api/agent/step")
    public ResponseEntity<Map<String,Object>> agentStep(
            @RequestHeader(value="X-Kapu-Token", required=false) String token,
            @RequestBody(required=false) Map<String,Object> body) {
        if (!operatorAllowed(token)) return unauthorized();
        Map<String,Object> result = background.step(body != null && Boolean.TRUE.equals(body.get("force")));
        result.put("status",background.status());
        sse.broadcast();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/agent/toggle")
    public ResponseEntity<Map<String,Object>> agentToggle(
            @RequestHeader(value="X-Kapu-Token", required=false) String token,
            @RequestBody Map<String,Object> body) {
        if (!operatorAllowed(token)) return unauthorized();
        if (!(body.get("start") instanceof Boolean)) return ResponseEntity.badRequest().build();
        return saveSettings(null,(Boolean) body.get("start"),null);
    }

    @PostMapping("/api/agent/interval")
    public ResponseEntity<Map<String,Object>> agentInterval(
            @RequestHeader(value="X-Kapu-Token", required=false) String token,
            @RequestBody Map<String,Object> body) {
        if (!operatorAllowed(token)) return unauthorized();
        Object seconds = body.get("tickSeconds");
        if (!Arrays.asList(300,900,1800).contains(seconds)) return ResponseEntity.badRequest().build();
        return saveSettings(null,null,(Integer) seconds);
    }

    private boolean operatorAllowed(String token) {
        String required = properties.getApiToken();
        if (required == null || required.isEmpty()) return true;
        return token != null && token.equals(required);
    }

    private ResponseEntity<Map<String,Object>> saveSettings(Boolean paused, Boolean enabled, Integer seconds) {
        try {
            Map<String,Object> result = background.updateSettings(paused,enabled,seconds);
            sse.broadcast();
            return ResponseEntity.ok(result);
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500).body(java.util.Collections.singletonMap("error","settings-write-failed"));
        }
    }

    @GetMapping("/api/screen")
    public ResponseEntity<Map<String, Object>> screen(
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "mode", required = false) String mode) {
        Map<String, Object> map = new LinkedHashMap<>();
        com.bytedance.douyinclouddemo.model.Snippet snippet = game.snippetForScreen(id, mode);
        if (snippet == null) {
            map.put("ok", false);
            map.put("error", "no_matching_preview");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(map);
        }
        com.bytedance.douyinclouddemo.model.ScreenImage image = screenRender.render(snippet, mode);
        if (image == null) {
            map.put("ok", false);
            map.put("error", "render_unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(map);
        }
        map.put("ok", true);
        map.put("id", snippet.getId());
        map.put("phase", mode);
        map.put("image", image.getDataUrl());
        return ResponseEntity.ok(map);
    }

    @GetMapping(value = "/screen.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> screenJpg(
            @RequestParam(value = "id", required = false) String id,
            @RequestParam(value = "mode", required = false) String mode) {
        com.bytedance.douyinclouddemo.model.Snippet snippet = game.snippetForScreen(id, mode);
        if (snippet == null) return ResponseEntity.notFound().build();
        com.bytedance.douyinclouddemo.model.ScreenImage image = screenRender.render(snippet, mode);
        if (image == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.IMAGE_JPEG)
                .body(image.getJpeg());
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
