package com.bytedance.douyinclouddemo;

import com.bytedance.douyinclouddemo.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.ai-paused=true", "app.data-dir=target/test-data-api", "app.workspace=target/test-workspace-api", "app.api-token=test-token"})
@AutoConfigureMockMvc
class GameApiControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private GameService game;

    @org.junit.jupiter.api.Test
    void screenWithoutMatchingSnippetIs404() throws Exception {
        mvc.perform(get("/api/screen").param("id", "nope").param("mode", "preview"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @BeforeEach
    void reset() {
        game.control("reset");
    }

    @Test
    void stateIsPublicButControlNeedsToken() throws Exception {
        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("work"));

        mvc.perform(post("/api/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"play\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/control")
                        .header("X-Kapu-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"play\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void liveCallbackIsAcceptedAndActionNeedsToken() throws Exception {
        mvc.perform(post("/api/action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"comment\",\"user\":\"小橘\",\"command\":\"1\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/action")
                        .header("X-Kapu-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"comment\",\"user\":\"小橘\",\"command\":\"1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(post("/live_data_callback")
                        .header("x-msg-type", "live_gift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"msg_id\":\"api-gift-1\",\"data\":{\"giftName\":\"咖啡\",\"userInfo\":{\"userName\":\"小满\"}}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.err_no").value(0));
    }
}

// 追加在类末尾的说明：screen 无匹配 snippet 时应直接 404，不启动 Chromium
