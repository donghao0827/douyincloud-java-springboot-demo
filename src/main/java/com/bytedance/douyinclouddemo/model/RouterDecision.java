package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class RouterDecision {
    private String route;
    private String preset = "none";
    private String task = "none";
    private String reason = "";
}
