package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class ChatUsage {
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private int cacheHitTokens;
    private int cacheMissTokens;
    private int reasoningTokens;
}
