package com.bytedance.douyinclouddemo.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class GameState {
    private String phase = "work";
    private String phaseLabel = "认真上班";
    private long phaseStartedAt;
    private Long phaseEndsAt;
    private Long voteDeadline;
    private int round;
    private int risk;
    private boolean extended;
    private boolean paused;
    private Long pausedAt;
    private Map<String, Integer> votes = new LinkedHashMap<>();
    private Map<String, Integer> voters = new LinkedHashMap<>();
    private Map<String, Player> players = new LinkedHashMap<>();
    private List<FeedItem> feed = new ArrayList<>();
    private String aiMode = "idle";
    private String lastNarration = "";
    private List<Map<String, Object>> tasks = new ArrayList<>();
    private Snippet snippet;
    private Snippet lastTask;
    private Snippet projectSnippet;
    private Map<String, Object> project = new LinkedHashMap<>();
    private Map<String, Object> currentGame;
    private String playSnippetId;
    private Snippet screenBeforePlay;
    private Map<String, Object> lastResult;
    private Map<String, Integer> stats = new LinkedHashMap<>();
    private long createdAt;
    private long updatedAt;
}
