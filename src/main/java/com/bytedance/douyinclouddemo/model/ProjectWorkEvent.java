package com.bytedance.douyinclouddemo.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProjectWorkEvent {
    private String title;
    private String file;
    private String html;
    private String summary;
}
