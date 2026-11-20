package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AiGate {
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AppProperties properties;

    public AiGate(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        paused.set(properties.isAiPaused());
    }

    public boolean isPaused() {
        return paused.get();
    }

    public boolean setPaused(boolean value) {
        paused.set(value);
        return value;
    }
}
