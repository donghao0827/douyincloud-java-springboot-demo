package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class ActionEvent {
    private String type;
    private String user = "观众";
    private String command;
    private Integer count;
    private String gift;
    private String eventId;
    private long at = System.currentTimeMillis();
}
