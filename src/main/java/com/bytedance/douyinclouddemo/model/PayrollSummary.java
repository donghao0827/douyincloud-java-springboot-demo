package com.bytedance.douyinclouddemo.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class PayrollSummary {
    private String month;
    private int base;
    private int gifts;
    private int fines;
    private int tokens;
    private double cost;
    private double actual;
    private List<Map<String, Object>> recent = new ArrayList<>();
}
