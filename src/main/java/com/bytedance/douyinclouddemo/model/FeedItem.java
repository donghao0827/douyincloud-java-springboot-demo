package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class FeedItem {
    private String id;
    private String kind;
    private String text;
    private String user;
    private long at;

    public FeedItem(String id, String kind, String text, String user, long at) {
        this.id = id;
        this.kind = kind;
        this.text = text;
        this.user = user;
        this.at = at;
    }
}
