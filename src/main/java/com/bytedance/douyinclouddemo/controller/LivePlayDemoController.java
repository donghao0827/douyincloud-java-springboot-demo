package com.bytedance.douyinclouddemo.controller;

import com.bytedance.douyinclouddemo.model.JsonResponse;
import com.bytedance.douyinclouddemo.service.GameService;
import com.bytedance.douyinclouddemo.service.LivePlayService;
import com.bytedance.douyinclouddemo.service.SseService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 抖音云 x 弹幕玩法服务端。
 * /start_game 开启直播数据推送；/live_data_callback 接收评论/点赞/礼物/进场。
 */
@RestController
public class LivePlayDemoController {
    private final LivePlayService livePlay;
    private final GameService game;
    private final SseService sse;

    public LivePlayDemoController(LivePlayService livePlay, GameService game, SseService sse) {
        this.livePlay = livePlay;
        this.game = game;
        this.sse = sse;
    }

    @PostMapping("/start_game")
    public JsonResponse startGame(HttpServletRequest request) {
        String appId = request.getHeader("X-TT-AppID");
        String roomId = request.getHeader("X-Room-ID");
        String anchor = request.getHeader("X-Anchor-OpenID");
        System.out.println("[liveplay] start app=" + appId + " room=" + roomId + " anchor=" + anchor);
        boolean ok = livePlay.startGame(appId, roomId);
        sse.broadcast();
        JsonResponse response = new JsonResponse();
        if (ok) {
            response.success("开始玩法对局成功");
        } else {
            response.failure("开启直播数据推送失败，请检查 AppID/RoomID 或内网权限");
        }
        return response;
    }

    @PostMapping("/finish_game")
    public JsonResponse finishGame() {
        livePlay.finishGame();
        game.control("finish");
        sse.broadcast();
        JsonResponse response = new JsonResponse();
        response.success("结束玩法成功");
        return response;
    }

    @PostMapping("/live_data_callback")
    public JsonResponse liveDataCallback(
            @RequestHeader(value = "X-Anchor-OpenID", required = false) String anchorOpenId,
            @RequestHeader(value = "x-msg-type", required = false) String msgType,
            @RequestBody String body) {
        livePlay.onCallback(msgType, body, anchorOpenId);
        JsonResponse response = new JsonResponse();
        response.success("success");
        return response;
    }

    @RequestMapping(value = "/websocket_callback", method = {RequestMethod.POST, RequestMethod.GET})
    public JsonResponse websocketCallback() {
        JsonResponse response = new JsonResponse();
        response.success("success");
        return response;
    }
}
