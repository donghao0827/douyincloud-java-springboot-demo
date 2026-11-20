package com.bytedance.douyinclouddemo;

import com.bytedance.douyinclouddemo.model.ActionEvent;
import com.bytedance.douyinclouddemo.service.LiveEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveEventMapperTest {
    private final LiveEventMapper mapper = new LiveEventMapper();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsCommentLikeAndGift() throws Exception {
        ActionEvent comment = mapper.map("live_comment", json.readTree(
                "{\"msg_id\":\"c1\",\"data\":{\"content\":\"老板来了\",\"userInfo\":{\"userName\":\"小橘\"}}}"));
        assertEquals("comment", comment.getType());
        assertEquals("小橘", comment.getUser());
        assertEquals("老板来了", comment.getCommand());

        ActionEvent like = mapper.map("live_like", json.readTree(
                "{\"msg_id\":\"l1\",\"data\":{\"count\":30,\"userInfo\":{\"userName\":\"阿禾\"}}}"));
        assertEquals("like", like.getType());
        assertEquals(30, like.getCount());

        ActionEvent gift = mapper.map("live_gift", json.readTree(
                "{\"msg_id\":\"g1\",\"data\":{\"giftName\":\"咖啡\",\"giftCount\":1,\"userInfo\":{\"userName\":\"洛洛\"}}}"));
        assertEquals("gift", gift.getType());
        assertEquals("coffee", gift.getGift());
    }

    @Test
    void mapsJoinCommentAndInout() throws Exception {
        ActionEvent join = mapper.map("live_comment", json.readTree(
                "{\"msg_id\":\"j1\",\"data\":{\"content\":\"加入\",\"userInfo\":{\"userName\":\"小满\"}}}"));
        assertEquals("join", join.getType());
        ActionEvent inout = mapper.map("live_inout", json.readTree(
                "{\"msg_id\":\"j2\",\"data\":{\"event\":1,\"userInfo\":{\"userName\":\"小满\"}}}"));
        assertEquals("join", inout.getType());
    }
}
