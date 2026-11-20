package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.*;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RuntimeService {
    private final AgentService agents;
    private final GameService game;
    private final SseService sse;
    private final DeepSeekService deepSeek;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "kapu-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicReference<ActionEvent> pending = new AtomicReference<>();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger processed = new AtomicInteger();
    private volatile String mode;
    private volatile Map<String, Object> last;

    public RuntimeService(AgentService agents, GameService game, SseService sse, DeepSeekService deepSeek) {
        this.agents = agents;
        this.game = game;
        this.sse = sse;
        this.deepSeek = deepSeek;
        this.mode = Boolean.TRUE.equals(deepSeek.config().get("hasKey")) ? "llm" : "no_key";
    }

    public synchronized Map<String, Object> submit(ActionEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (busy.get()) {
            boolean had = pending.get() != null;
            if (had) dropped.incrementAndGet();
            pending.set(event);
            result.put("status", had ? "replaced" : "pending");
            return result;
        }
        busy.set(true);
        result.put("status", "processing");
        executor.submit(() -> loop(event));
        return result;
    }

    private void loop(ActionEvent first) {
        ActionEvent current = first;
        try {
            while (current != null) {
                try {
                    processOne(current);
                } catch (Exception e) {
                    System.err.println("[runtime] " + e.getMessage());
                }
                current = pending.getAndSet(null);
            }
        } finally {
            busy.set(false);
        }
    }

    private void processOne(ActionEvent event) {
        Map<String, Object> snapshot = game.snapshot();
        long started = System.currentTimeMillis();

        ChatResult routed = agents.router(event, snapshot);
        RouterDecision route = routed.isOk() ? agents.sanitizeRouter(routed.getData()) : null;
        if (route == null) {
            route = localRoute(event);
            if ("llm".equals(mode)) mode = "fallback";
        } else if ("fallback".equals(mode) || "no_key".equals(mode)) {
            mode = "llm";
        }
        game.setAiMode(mode);

        if ("preset".equals(route.getRoute())) {
            game.applyPreset(route.getPreset(), event);
            processed.incrementAndGet();
            last = decision("preset", null, route.getPreset(), null, false, started);
            sse.broadcast();
            return;
        }
        if ("ignore".equals(route.getRoute())) {
            processed.incrementAndGet();
            last = decision("ignore", null, null, null, false, started);
            sse.broadcast();
            return;
        }
        if ("task".equals(route.getRoute()) && "none".equals(route.getTask())) {
            route.setRoute("judge");
        }
        if ("task".equals(route.getRoute()) && "game".equals(route.getTask())) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mode", mode);
            meta.put("preset", true);
            meta.put("taskKind", "game");
            game.applyTask(event, null, meta);
            processed.incrementAndGet();
            last = decision("task", "game", null, null, false, started);
            sse.broadcast();
            return;
        }
        if ("task".equals(route.getRoute())) {
            String taskName = route.getTask();
            ChatResult result = agents.task(event, snapshot, taskName);
            TaskDecision data = result.isOk() ? agents.sanitizeTask(result.getData()) : null;
            processed.incrementAndGet();
            if (data == null) {
                if ("llm".equals(mode)) mode = "fallback";
                game.setAiMode(mode);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("mode", mode);
                meta.put("degraded", true);
                meta.put("taskKind", taskName);
                game.applyTask(event, null, meta);
                last = decision("task", taskName, null, null, true, started);
            } else {
                game.applyTask(event, data, meta(mode, false));
                last = decision("task", data.getTask(), null, null, false, started);
            }
            sse.broadcast();
            return;
        }

        ChatResult judged = agents.judge(event, snapshot);
        JudgeDecision decision = judged.isOk() ? agents.sanitizeJudge(judged.getData()) : null;
        boolean degraded = false;
        if (decision == null) {
            decision = localJudge(event, snapshot);
            degraded = true;
            if ("llm".equals(mode)) mode = "fallback";
            game.setAiMode(mode);
        }
        processed.incrementAndGet();
        game.applyJudge(event, decision, meta(mode, degraded));
        last = decision("judge", null, null, decision.getNarration(), degraded, started);
        sse.broadcast();
    }

    private Map<String, Object> meta(String mode, boolean degraded) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", mode);
        meta.put("degraded", degraded);
        return meta;
    }

    private Map<String, Object> decision(String route, String task, String preset, String narration,
                                         boolean degraded, long started) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("at", System.currentTimeMillis());
        map.put("route", route);
        if (task != null) map.put("task", task);
        if (preset != null) map.put("preset", preset);
        if (narration != null) map.put("narration", narration);
        map.put("degraded", degraded);
        map.put("latencyMs", System.currentTimeMillis() - started);
        return map;
    }

    public RouterDecision localRoute(ActionEvent event) {
        RouterDecision route = new RouterDecision();
        String command = event.getCommand() == null ? "" : event.getCommand().trim();
        if ("join".equals(event.getType())) return preset(route, "join", "本地:加入");
        if ("like".equals(event.getType())) return preset(route, "like", "本地:点赞");
        if ("gift".equals(event.getType())) return preset(route, "gift", "本地:礼物");
        if ("comment".equals(event.getType())) {
            if ("1".equals(command)) return preset(route, "vote:1", "本地:提醒收手");
            if ("2".equals(command)) return preset(route, "vote:2", "本地:再玩五秒");
            if ("加入".equals(command)) return preset(route, "join", "本地:加入");
            if (command.isEmpty()) {
                route.setRoute("ignore");
                route.setReason("本地:空评论");
                return route;
            }
            route.setRoute("judge");
            route.setReason("本地:自由评论");
            return route;
        }
        route.setRoute("ignore");
        route.setReason("本地:未知类型");
        return route;
    }

    private RouterDecision preset(RouterDecision route, String preset, String reason) {
        route.setRoute("preset");
        route.setPreset(preset);
        route.setReason(reason);
        return route;
    }

    public JudgeDecision localJudge(ActionEvent event, Map<String, Object> snapshot) {
        String command = event.getCommand() == null ? "" : event.getCommand();
        String user = event.getUser() == null ? "观众" : event.getUser();
        JudgeDecision decision = new JudgeDecision();
        Map<String, Object> effect = new LinkedHashMap<>();
        if (command.matches(".*(收手|别玩|快工作|工作去|老板|查岗|回来).*")) {
            decision.setNarration(user + " 喊卡噗快收手，记一票。");
            effect.put("kind", "vote");
            effect.put("vote", 1);
            effect.put("delta", 0);
            effect.put("action", "none");
            decision.setMood("calm");
        } else if (command.matches(".*(再玩|继续|多玩|别收|接着玩|别停).*")) {
            decision.setNarration(user + " 支持再玩五秒，卡噗眼睛亮了。");
            effect.put("kind", "vote");
            effect.put("vote", 2);
            effect.put("delta", 0);
            effect.put("action", "none");
            decision.setMood("celebrate");
        } else if (command.matches(".*(摸鱼|玩游戏|划水).*")) {
            decision.setNarration(user + " 怂恿卡噗摸鱼，掌机在抽屉里发亮。");
            effect.put("kind", "action");
            effect.put("vote", null);
            effect.put("delta", 0);
            effect.put("action", "play");
            decision.setMood("tense");
        } else if (command.matches(".*(咖啡|喝点|提神).*")) {
            decision.setNarration(user + " 递来一杯咖啡，卡噗清醒了一点。");
            effect.put("kind", "action");
            effect.put("vote", null);
            effect.put("delta", 0);
            effect.put("action", "coffee");
            decision.setMood("calm");
        } else if (command.matches(".*(看.*(页面|代码|写什么)|预览).*")) {
            decision.setNarration(user + " 想看看卡噗在写什么。");
            effect.put("kind", "action");
            effect.put("vote", null);
            effect.put("delta", 0);
            effect.put("action", "peek");
            decision.setMood("calm");
        } else {
            decision.setNarration(user + " 的话，卡噗听见了，先记在本子上。");
            effect.put("kind", "noop");
            effect.put("vote", null);
            effect.put("delta", 0);
            effect.put("action", "none");
            decision.setMood("calm");
        }
        decision.setEffect(effect);
        return decision;
    }

    public Map<String, Object> stats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode);
        map.put("busy", busy.get());
        map.put("pending", pending.get() != null ? 1 : 0);
        map.put("dropped", dropped.get());
        map.put("processed", processed.get());
        map.put("last", last);
        return map;
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
