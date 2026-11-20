package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.ActionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class LiveEventMapper {
    private final ObjectMapper mapper = new ObjectMapper();

    public ActionEvent map(String headerType, JsonNode item) {
        JsonNode data = item;
        if (item.has("data")) {
            JsonNode raw = item.get("data");
            if (raw.isTextual()) {
                try {
                    data = mapper.readTree(raw.asText());
                } catch (Exception ignored) {
                }
            } else if (raw.isObject()) {
                data = raw;
            }
        }
        String type = headerType;
        if (type == null || type.isEmpty()) type = item.path("msg_type").asText("live_comment");

        ActionEvent event = new ActionEvent();
        event.setAt(System.currentTimeMillis());
        event.setEventId(eventId(item, data, type));
        event.setUser(userName(data));

        if ("live_comment".equals(type) || "live_comment".equals(item.path("msg_type").asText())) {
            String content = text(data, "content", "comment", "text");
            if (content == null || content.trim().isEmpty()) return null;
            event.setCommand(content.trim());
            event.setType("加入".equals(content.trim()) ? "join" : "comment");
            return event;
        }
        if ("live_like".equals(type)) {
            event.setType("like");
            event.setCount(Math.max(1, intValue(data, "count", "likeCount", "like_count")));
            return event;
        }
        if ("live_gift".equals(type)) {
            String giftName = text(data, "giftName", "gift_name", "name");
            if (giftName == null) giftName = data.path("gift").path("name").asText("");
            event.setType("gift");
            event.setGift(mapGift(giftName));
            return event;
        }
        if ("live_inout".equals(type) || "live_fansclub".equals(type) || "live_enter".equals(type)) {
            event.setType("join");
            return event;
        }
        event.setType("comment");
        event.setCommand(text(data, "content", "comment", "text"));
        return event.getCommand() == null || event.getCommand().trim().isEmpty() ? null : event;
    }

    private String eventId(JsonNode item, JsonNode data, String type) {
        String id = item.path("msg_id").asText("");
        if (id.isEmpty()) id = data.path("msg_id").asText("");
        if (id.isEmpty()) id = data.path("msgId").asText("");
        if (!id.isEmpty()) return id;
        return UUID.nameUUIDFromBytes((type + item.toString()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String userName(JsonNode data) {
        String name = text(data.path("userInfo"), "userName", "nickname", "nickName", "user_name");
        if (name == null) name = text(data.path("user"), "nickname", "nickName", "userName", "user_name");
        if (name == null) name = text(data, "nickname", "nickName", "userName", "user_name");
        if (name == null || name.isEmpty()) name = "观众";
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    private String text(JsonNode node, String... names) {
        if (node == null || node.isMissingNode()) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isEmpty()) return text;
            }
        }
        return null;
    }

    private int intValue(JsonNode node, String... names) {
        if (node == null) return 1;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) return value.asInt();
            if (value != null && value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (Exception ignored) {
                }
            }
        }
        return 1;
    }

    private String mapGift(String name) {
        String gift = name == null ? "" : name.toLowerCase();
        if (gift.contains("咖啡") || gift.contains("coffee") || gift.contains("拿铁")) return "coffee";
        if (gift.contains("键盘") || gift.contains("keyboard")) return "keyboard";
        if (gift.contains("显示器") || gift.contains("monitor") || gift.contains("屏幕")) return "monitor";
        if (gift.contains("带薪") || gift.contains("假") || gift.contains("vacation")) return "vacation";
        return "coffee";
    }
}
