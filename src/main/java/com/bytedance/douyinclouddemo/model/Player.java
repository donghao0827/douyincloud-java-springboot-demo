package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class Player {
    private String name;
    private int score;
    private int likes;
    private int gifts;
    private int votes;
    private int tasks;
    private long joinedAt;

    public Player(String name, long joinedAt) {
        this.name = name;
        this.joinedAt = joinedAt;
    }
}
