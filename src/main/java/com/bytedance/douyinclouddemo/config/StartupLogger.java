package com.bytedance.douyinclouddemo.config;

import com.bytedance.douyinclouddemo.service.AiGate;
import com.bytedance.douyinclouddemo.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StartupLogger implements ApplicationRunner {
    private final DeepSeekService deepSeek;
    private final AiGate gate;
    private final AppProperties props;
    @Value("${server.port:8000}")
    private int port;

    public StartupLogger(DeepSeekService deepSeek, AiGate gate, AppProperties props) {
        this.deepSeek = deepSeek;
        this.gate = gate;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> cfg = deepSeek.config();
        System.out.println("[kapu] port=" + port
                + " model=" + cfg.get("model")
                + " hasKey=" + cfg.get("hasKey")
                + " key=" + cfg.get("keyFingerprint")
                + " aiPaused=" + gate.isPaused()
                + " apiToken=" + (props.getApiToken() != null && !props.getApiToken().isEmpty() ? "on" : "off")
                + " background=" + props.isBackgroundEnabled()
                + " dataDir=" + props.getDataDir());
    }
}
