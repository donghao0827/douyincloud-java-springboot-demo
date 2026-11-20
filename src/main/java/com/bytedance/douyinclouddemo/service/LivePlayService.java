package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.ActionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class LivePlayService {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String TASK_START_URL = "http://webcast-bytedance-com.openapi.dyc.ivolces.com/api/live_data/task/start";
    private static final String TASK_STOP_URL = "http://webcast-bytedance-com.openapi.dyc.ivolces.com/api/live_data/task/stop";
    private static final List<String> MSG_TYPES = Arrays.asList("live_like", "live_comment", "live_gift", "live_fansclub", "live_inout");

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();
    private final RuntimeService runtime;
    private final GameService game;
    private final LiveEventMapper mapperService;
    private volatile String lastAppId = "";
    private volatile String lastRoomId = "";
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>(600, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 500;
        }
    };

    public LivePlayService(RuntimeService runtime, GameService game, LiveEventMapper mapperService) {
        this.runtime = runtime;
        this.game = game;
        this.mapperService = mapperService;
    }

    public boolean startGame(String appId, String roomId) {
        if (appId == null || appId.isEmpty() || roomId == null || roomId.isEmpty()) return false;
        lastAppId = appId;
        lastRoomId = roomId;
        game.control("reset");
        boolean all = true;
        for (String msgType : MSG_TYPES) {
            all = startTask(appId, roomId, msgType) && all;
        }
        return all;
    }

    public void finishGame() {
        if (lastAppId.isEmpty() || lastRoomId.isEmpty()) return;
        for (String msgType : MSG_TYPES) {
            stopTask(lastAppId, lastRoomId, msgType);
        }
    }

    private boolean startTask(String appId, String roomId, String msgType) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("roomid", roomId);
            body.put("appid", appId);
            body.put("msg_type", msgType);
            Request request = new Request.Builder()
                    .url(TASK_START_URL)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, mapper.writeValueAsBytes(body)))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("[liveplay] start " + msgType + " HTTP " + response.code());
                    return false;
                }
                String text = response.body() == null ? "" : response.body().string();
                JsonNode node = mapper.readTree(text);
                if (node.path("err_no").asInt(0) != 0) {
                    System.err.println("[liveplay] start " + msgType + " err " + text);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            System.err.println("[liveplay] start " + msgType + " exception " + e.getMessage());
            return false;
        }
    }

    private boolean stopTask(String appId, String roomId, String msgType) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("roomid", roomId);
            body.put("appid", appId);
            body.put("msg_type", msgType);
            Request request = new Request.Builder()
                    .url(TASK_STOP_URL)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(JSON, mapper.writeValueAsBytes(body)))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("[liveplay] stop " + msgType + " HTTP " + response.code());
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            System.err.println("[liveplay] stop " + msgType + " exception " + e.getMessage());
            return false;
        }
    }

    public int onCallback(String headerType, String body, String anchorOpenId) {
        if (body == null || body.trim().isEmpty()) return 0;
        int count = 0;
        try {
            JsonNode root = mapper.readTree(body);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    count += accept(headerType, item);
                }
            } else {
                count += accept(headerType, root);
            }
            System.out.println("[liveplay] callback " + headerType + " anchor=" + anchorOpenId + " events=" + count);
            if (count == 0) System.out.println("[liveplay] raw=" + (body.length() > 2000 ? body.substring(0, 2000) : body));
        } catch (Exception e) {
            System.err.println("[liveplay] callback parse failed: " + e.getMessage());
        }
        return count;
    }

    private int accept(String type, JsonNode item) {
        ActionEvent event = mapperService.map(type, item);
        if (event == null) return 0;
        synchronized (seen) {
            if (event.getEventId() != null && seen.containsKey(event.getEventId())) return 0;
            if (event.getEventId() != null) seen.put(event.getEventId(), System.currentTimeMillis());
        }
        runtime.submit(event);
        return 1;
    }
}
