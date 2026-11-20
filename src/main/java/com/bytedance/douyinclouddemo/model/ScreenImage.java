package com.bytedance.douyinclouddemo.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScreenImage {
    private byte[] jpeg;
    private String dataUrl;
}
