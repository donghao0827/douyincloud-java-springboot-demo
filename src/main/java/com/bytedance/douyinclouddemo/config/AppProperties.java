package com.bytedance.douyinclouddemo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private boolean aiPaused = false;
    private String dataDir = "data";
    private String apiToken = "";
}
