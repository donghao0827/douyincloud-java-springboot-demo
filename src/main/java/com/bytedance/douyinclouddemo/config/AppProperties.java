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
    private boolean backgroundEnabled = false;
    private String workStart = "09:00";
    private String workEnd = "18:00";
    private String workDays = "1,2,3,4,5,6,7";
    private int tickSeconds = 300;
    private String workspace = "workspace";
    private String chromePath = "";
}
