package com.bytedance.douyinclouddemo.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ChatResult {
    private boolean ok;
    private String content = "";
    private JsonNode data;
    private ChatUsage usage = new ChatUsage();
    private long latencyMs;
    private String finishReason;
    private String errorType;
    private String errorMessage;
}
