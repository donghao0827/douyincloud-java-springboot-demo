package com.bytedance.douyinclouddemo.model;

import lombok.Data;

import java.util.Map;

@Data
public class Snippet {
    private String id;
    private String task;
    private String title;
    private String html;
    private String user;
    private String reply;
    private Map<String, Object> draw;
    private long at;
}
