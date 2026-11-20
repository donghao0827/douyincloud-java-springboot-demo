package com.bytedance.douyinclouddemo;

import com.bytedance.douyinclouddemo.model.TaskDecision;
import com.bytedance.douyinclouddemo.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentServiceTest {
    private final AgentService agent = new AgentService(null, null);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsAvatarDrawCommandsWithoutHtml() throws Exception {
        TaskDecision decision = agent.sanitizeTask(json.readTree(
                "{\"task\":\"avatar\",\"title\":\"水豚\",\"reply\":\"画好了\",\"draw\":{" +
                        "\"bg\":\"#1b120b\",\"shapes\":[" +
                        "{\"type\":\"circle\",\"cx\":100,\"cy\":90,\"r\":50,\"fill\":\"#c9a06a\"}," +
                        "{\"type\":\"line\",\"x1\":30,\"y1\":30,\"x2\":170,\"y2\":30,\"stroke\":\"#333333\",\"width\":3}" +
                        "]}}"));
        assertNotNull(decision);
        assertEquals("avatar", decision.getTask());
        assertNull(decision.getHtml());
        assertNotNull(decision.getDraw());
        assertEquals("#1b120b", decision.getDraw().get("bg"));
    }

    @Test
    void rejectsAvatarWithoutValidDrawAndFallsBackToPageRules() throws Exception {
        assertNull(agent.sanitizeTask(json.readTree(
                "{\"task\":\"avatar\",\"title\":\"水豚\",\"reply\":\"画好了\",\"html\":\"<div>no draw</div>\"}")));
        assertNull(agent.sanitizeTask(json.readTree(
                "{\"task\":\"avatar\",\"draw\":{\"shapes\":[{\"type\":\"circle\",\"cx\":100,\"cy\":100}]}}")));
    }

    @Test
    void stillAcceptsSafeAdHtml() throws Exception {
        TaskDecision decision = agent.sanitizeTask(json.readTree(
                "{\"task\":\"ad\",\"title\":\"咖啡\",\"reply\":\"来一杯\",\"html\":\"<div style=\\\"color:#333\\\">摸鱼拿铁</div>\"}"));
        assertNotNull(decision);
        assertEquals("ad", decision.getTask());
        assertNotNull(decision.getHtml());
    }
}
