package com.bytedance.douyinclouddemo.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseService {
    private final GameService game;
    private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();

    public SseService(GameService game) {
        this.game = game;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(error -> clients.remove(emitter));
        try {
            emitter.send(SseEmitter.event().data(game.publicState()));
        } catch (IOException e) {
            clients.remove(emitter);
        }
        return emitter;
    }

    public void broadcast() {
        if (clients.isEmpty()) return;
        Map<String, Object> state = game.publicState();
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(SseEmitter.event().data(state));
            } catch (Exception e) {
                clients.remove(emitter);
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void heartbeat() {
        broadcast();
    }
}
