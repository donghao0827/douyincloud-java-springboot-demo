package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class Snippet {
    private String id;
    private String task;
    private String title;
    private String html;
    private String user;
    private String reply;
    private long at;
}
