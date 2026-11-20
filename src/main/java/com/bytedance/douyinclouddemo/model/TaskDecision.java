package com.bytedance.douyinclouddemo.model;

import lombok.Data;

import java.util.Map;

@Data
public class TaskDecision {
    private String task;
    private String title;
    private String reply;
    private String html;
    private Map<String, Object> draw;
}
