package com.bytedance.douyinclouddemo.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class JudgeDecision {
    private String narration;
    private Map<String, Object> effect = new LinkedHashMap<>();
    private String mood = "calm";
}
