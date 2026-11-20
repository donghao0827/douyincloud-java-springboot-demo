package com.bytedance.douyinclouddemo;

import com.bytedance.douyinclouddemo.model.ActionEvent;
import com.bytedance.douyinclouddemo.model.Game;
import com.bytedance.douyinclouddemo.model.Snippet;
import org.junit.jupiter.api.BeforeEach;
import com.bytedance.douyinclouddemo.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.ai-paused=true", "app.data-dir=target/test-data"})
class GameServiceTest {
    @Autowired
    private GameService game;

    @BeforeEach
    void reset() {
        game.control("reset");
    }

    @Test
    void startsInWorkAndOpensRandomGame() {
        assertEquals("work", game.getPhase());
        Map<String, Object> result = game.beginPlay();
        assertEquals(Boolean.TRUE, result.get("ok"));
        Map<String, Object> state = game.publicState();
        assertEquals("pickup", state.get("phase"));
        Snippet snippet = (Snippet) ((Map<String, Object>) state.get("ai")).get("snippet");
        assertEquals("game", snippet.getTask());
        assertNotNull(state.get("currentGame"));
        game.control("reset");
    }

    @Test
    void gameTaskUsesPresetAndIsPlayableHtml() {
        ActionEvent event = new ActionEvent();
        event.setType("comment");
        event.setUser("小橘");
        event.setCommand("来个游戏");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskKind", "game");
        meta.put("preset", true);
        game.applyTask(event, null, meta);
        Map<String, Object> state = game.publicState();
        Snippet snippet = (Snippet) ((Map<String, Object>) state.get("ai")).get("snippet");
        assertEquals("game", snippet.getTask());
        assertTrue(String.valueOf(snippet.getHtml()).contains("<script>"));
        game.control("reset");
    }
}
