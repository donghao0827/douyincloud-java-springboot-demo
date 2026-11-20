package com.bytedance.douyinclouddemo.model;

import lombok.Data;

@Data
public class CostResult {
    private double cny;
    private String band;

    public CostResult(double cny, String band) {
        this.cny = cny;
        this.band = band;
    }
}
