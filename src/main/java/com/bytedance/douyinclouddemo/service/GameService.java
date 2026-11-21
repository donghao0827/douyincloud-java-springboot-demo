package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.model.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GameService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int PLAY_SECONDS = 8;
    private static final int EXTEND_SECONDS = 5;
    private static final int RISK_LIMIT = 100;
    private static final int PLAY_RISK = 35;
    private static final int EXTEND_RISK = 15;
    private static final int COFFEE_RISK = 10;
    private static final Map<String, String> PHASE_LABELS = new LinkedHashMap<>();
    private static final Map<String, Integer> PHASE_DURATIONS = new LinkedHashMap<>();
    private static final List<String> TASK_KINDS = Arrays.asList("ad", "avatar", "game", "todo", "code");
    private static final List<String> GAME_KEYS = Arrays.asList("coffee", "keyboard", "monitor", "vacation");

    static {
        phase("work", "认真上班", 0);
        phase("pickup", "悄悄拿掌机", 1000);
        phase("play", "偷偷摸鱼", 8000);
        phase("hide", "快藏起来", 1000);
        phase("caught", "老板来了！", 15000);
        phase("coffee", "喝杯咖啡", 2000);
        phase("preview", "展示工作成果", 4500);
        phase("avatar", "给观众画头像", 5500);
    }

    private static void phase(String name, String label, int duration) {
        PHASE_LABELS.put(name, label);
        PHASE_DURATIONS.put(name, duration);
    }

    private final PayrollService payroll;
    private final UsageLedgerService ledger;
    private final AssetService assets;
    private final DeepSeekService deepSeek;
    private final BackgroundAgentService background;
    private GameState state;

    public GameService(PayrollService payroll, UsageLedgerService ledger, AssetService assets,
                       DeepSeekService deepSeek, BackgroundAgentService background) {
        this.payroll = payroll;
        this.ledger = ledger;
        this.assets = assets;
        this.deepSeek = deepSeek;
        this.background = background;
    }

    @PostConstruct
    public void init() {
        state = fresh();
    }

    private GameState fresh() {
        long at = System.currentTimeMillis();
        GameState next = new GameState();
        next.setPhase("work");
        next.setPhaseLabel(PHASE_LABELS.get("work"));
        next.setPhaseStartedAt(at);
        next.setVotes(votes(0, 0));
        next.getStats().put("caught", 0);
        next.getStats().put("tasks", 0);
        next.getStats().put("votes", 0);
        next.getStats().put("playSeconds", 0);
        next.getStats().put("likes", 0);
        next.getStats().put("games", 0);
        Snippet work = workScreenSnippet(at);
        work.setId("starter-coffee-page");
        work.setTitle("小橘咖啡 · 今日菜单");
        work.setHtml(assets.screen("menu"));
        next.setSnippet(work);
        next.setProjectSnippet(work);
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("title", "小橘咖啡 · 今日菜单");
        project.put("summary", "今日菜单已打开");
        project.put("file", "artifacts/index.html");
        project.put("updatedAt", at);
        next.setProject(project);
        next.setAiMode("idle");
        next.setCreatedAt(at);
        next.setUpdatedAt(at);
        next.getFeed().add(new FeedItem(UUID.randomUUID().toString(), "system",
                "卡噗坐到工位，打开电脑，准备上班。", "", at));
        return next;
    }

    private Map<String, Integer> votes(int one, int two) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("1", one);
        map.put("2", two);
        return map;
    }

    private Snippet workScreenSnippet(long at) {
        Snippet snippet = new Snippet();
        snippet.setId("work-screen");
        snippet.setTask("code");
        snippet.setTitle("work.js");
        snippet.setHtml(assets.work());
        snippet.setUser("卡噗");
        snippet.setReply("");
        snippet.setAt(at);
        return snippet;
    }

    public synchronized void setAiMode(String mode) {
        state.setAiMode(mode);
    }

    private void addFeed(String kind, String text, String user) {
        long at = System.currentTimeMillis();
        state.getFeed().add(0, new FeedItem(UUID.randomUUID().toString(), kind, text, user == null ? "" : user, at));
        while (state.getFeed().size() > 40) state.getFeed().remove(state.getFeed().size() - 1);
        state.setUpdatedAt(at);
    }

    private Player getPlayer(String name) {
        String key = name == null ? "观众" : name.trim();
        if (key.isEmpty()) key = "观众";
        if (key.length() > 16) key = key.substring(0, 16);
        Player player = state.getPlayers().get(key);
        if (player == null) {
            player = new Player(key, System.currentTimeMillis());
            state.getPlayers().put(key, player);
            addFeed("join", "来到工位旁", key);
        }
        return player;
    }

    private void setPhase(String phase, long duration) {
        long at = System.currentTimeMillis();
        state.setPhase(phase);
        state.setPhaseLabel(PHASE_LABELS.getOrDefault(phase, phase));
        state.setPhaseStartedAt(at);
        state.setPhaseEndsAt(duration > 0 ? at + duration : null);
        if (!"play".equals(phase)) state.setVoteDeadline(null);
        state.setUpdatedAt(at);
    }

    private boolean addRisk(int amount) {
        state.setRisk(Math.max(0, Math.min(RISK_LIMIT, state.getRisk() + amount)));
        if (state.getRisk() >= RISK_LIMIT) {
            caught("摸鱼值拉满，獾主管来查岗");
            return false;
        }
        return true;
    }

    private void restoreProjectScreen() {
        if (state.getProjectSnippet() != null) {
            state.setSnippet(state.getProjectSnippet());
        } else {
            state.setSnippet(workScreenSnippet(System.currentTimeMillis()));
        }
    }

    @org.springframework.context.event.EventListener
    public synchronized void onProjectWork(com.bytedance.douyinclouddemo.model.ProjectWorkEvent event) {
        if (event == null || event.getFile() == null || event.getFile().isEmpty()) return;
        long at = System.currentTimeMillis();
        String title = limit(event.getTitle(), 24);
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("title", title);
        project.put("summary", event.getSummary());
        project.put("file", event.getFile());
        project.put("updatedAt", at);
        state.setProject(project);
        Snippet snippet = new Snippet();
        snippet.setId("work-" + UUID.randomUUID());
        snippet.setTask("code");
        snippet.setTitle(title);
        snippet.setHtml(event.getHtml());
        snippet.setUser("卡噗");
        snippet.setReply(event.getSummary());
        snippet.setAt(at);
        state.setProjectSnippet(snippet);
        if ("work".equals(state.getPhase())) state.setSnippet(snippet);
        addFeed("work", "推进：" + event.getSummary(), "卡噗");
    }

    private void restorePlayScreen() {
        if (state.getPlaySnippetId() != null && state.getSnippet() != null
                && state.getPlaySnippetId().equals(state.getSnippet().getId())) {
            state.setSnippet(state.getScreenBeforePlay() != null ? state.getScreenBeforePlay()
                    : state.getProjectSnippet() != null ? state.getProjectSnippet() : workScreenSnippet(System.currentTimeMillis()));
        }
        state.setPlaySnippetId(null);
        state.setScreenBeforePlay(null);
        state.setCurrentGame(null);
    }

    private void caught(String reason) {
        if ("caught".equals(state.getPhase())) return;
        restorePlayScreen();
        state.setRisk(RISK_LIMIT);
        state.getStats().put("caught", state.getStats().getOrDefault("caught", 0) + 1);
        state.setVotes(votes(0, 0));
        state.getVoters().clear();
        state.setLastResult(null);
        setPhase("caught", 15000);
        state.setVoteDeadline(state.getPhaseEndsAt());
        addFeed("warning", reason + "，评论 1 扣一颗小橘，2 替卡噗求情", "");
    }

    private void settleInspection() {
        int loss = state.getVotes().getOrDefault("1", 0) > state.getVotes().getOrDefault("2", 0) ? 100 : 0;
        payroll.record("inspection", -loss / 100.0, loss > 0 ? "查岗表决扣薪" : "查岗表决免扣");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "caught");
        result.put("loss", loss);
        result.put("at", System.currentTimeMillis());
        state.setLastResult(result);
        state.setRisk(0);
        setPhase("work", 0);
        restoreProjectScreen();
        addFeed("system", loss > 0 ? "表决结束，扣 1 颗小橘" : "表决结束，本次免扣", "");
    }

    public synchronized Map<String, Object> beginPlay() {
        if (state.isPaused()) return result(false, "paused");
        if (!"work".equals(state.getPhase())) return result(false, "phase:" + state.getPhase());
        if (!addRisk(PLAY_RISK)) return result(false, "caught");
        state.setRound(state.getRound() + 1);
        state.setVotes(votes(0, 0));
        state.getVoters().clear();
        state.setExtended(false);
        state.setLastResult(null);

        Game game = assets.pickGame();
        String playId = "play-game-" + UUID.randomUUID();
        state.setScreenBeforePlay(state.getSnippet());
        state.setPlaySnippetId(playId);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("id", game.getId());
        current.put("title", game.getTitle());
        state.setCurrentGame(current);
        Snippet snippet = new Snippet();
        snippet.setId(playId);
        snippet.setTask("game");
        snippet.setTitle(game.getTitle());
        snippet.setHtml(game.getHtml());
        snippet.setUser("卡噗");
        snippet.setReply("");
        snippet.setAt(System.currentTimeMillis());
        state.setSnippet(snippet);
        state.getStats().put("games", state.getStats().getOrDefault("games", 0) + 1);
        addFeed("game", "卡噗打开了《" + game.getTitle() + "》", "卡噗");
        addFeed("system", "第 " + state.getRound() + " 轮：卡噗悄悄拿出掌机。评论 1 收手 / 2 再玩五秒", "");
        setPhase("pickup", 1000);
        Map<String, Object> result = result(true, null);
        result.put("round", state.getRound());
        return result;
    }

    private void openVote() {
        state.setVoteDeadline(System.currentTimeMillis() + PLAY_SECONDS * 1000L);
        setPhase("play", PLAY_SECONDS * 1000L);
        addFeed("system", "投票开始：1 提醒收手，2 支持再玩五秒", "");
    }

    public synchronized Map<String, Object> finishPlay(String reason) {
        if (!"play".equals(state.getPhase()) && !"pickup".equals(state.getPhase())) return result(false, "not-playing");
        restorePlayScreen();
        state.getStats().put("playSeconds", state.getStats().getOrDefault("playSeconds", 0)
                + PLAY_SECONDS + (state.isExtended() ? EXTEND_SECONDS : 0));
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("kind", "finish");
        last.put("remind", state.getVotes().getOrDefault("1", 0));
        last.put("extend", state.getVotes().getOrDefault("2", 0));
        last.put("extended", state.isExtended());
        last.put("at", System.currentTimeMillis());
        state.setLastResult(last);
        addFeed("system", reason + "，卡噗把掌机塞回抽屉。", "");
        setPhase("hide", 1000);
        return result(true, null);
    }

    public synchronized Map<String, Object> vote(String playerName, int choice) {
        if (choice != 1 && choice != 2) return result(false, "invalid-vote");
        if (!"play".equals(state.getPhase()) && !"caught".equals(state.getPhase())) return result(false, "vote-closed");
        if (state.isPaused()) return result(false, "vote-closed");
        if (state.getVoteDeadline() != null && System.currentTimeMillis() >= state.getVoteDeadline()) return result(false, "vote-closed");

        Player player = getPlayer(playerName);
        Integer previous = state.getVoters().get(player.getName());
        String label = "caught".equals(state.getPhase())
                ? (choice == 1 ? "扣薪" : "求情") : (choice == 1 ? "收手" : "再玩");
        if (previous != null && previous == choice) {
            addFeed("vote", "坚持选 " + choice + "（" + label + "）", player.getName());
            Map<String, Object> result = result(true, null);
            result.put("unchanged", true);
            return result;
        }
        if (previous != null) {
            state.getVotes().put(String.valueOf(previous), Math.max(0, state.getVotes().getOrDefault(String.valueOf(previous), 0) - 1));
        }
        state.getVoters().put(player.getName(), choice);
        state.getVotes().put(String.valueOf(choice), state.getVotes().getOrDefault(String.valueOf(choice), 0) + 1);
        player.setVotes(player.getVotes() + 1);
        player.setScore(player.getScore() + (previous != null ? 1 : 4));
        state.getStats().put("votes", state.getStats().getOrDefault("votes", 0) + 1);
        addFeed("vote", "选 " + choice + "（" + label + "）", player.getName());
        return result(true, null);
    }

    public synchronized Map<String, Object> resolveVote(boolean force) {
        if (!"play".equals(state.getPhase())) return result(false, "not-playing");
        if (!force && state.getVoteDeadline() != null && System.currentTimeMillis() < state.getVoteDeadline()) {
            return result(false, "still-open");
        }
        if (state.isExtended()) return finishPlay("延时结束");

        int remind = state.getVotes().getOrDefault("1", 0);
        int extend = state.getVotes().getOrDefault("2", 0);
        if (extend > remind && !state.isExtended()) {
            state.setExtended(true);
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("kind", "extend");
            last.put("remind", remind);
            last.put("extend", extend);
            last.put("at", System.currentTimeMillis());
            state.setLastResult(last);
            addFeed("success", "再玩五秒胜出（" + extend + " : " + remind + "），摸鱼延长 5 秒", "");
            if (!addRisk(EXTEND_RISK)) return result(false, "caught");
            state.setVoteDeadline(System.currentTimeMillis() + EXTEND_SECONDS * 1000L);
            state.setPhaseEndsAt(state.getVoteDeadline());
            Map<String, Object> result = result(true, null);
            result.put("extended", true);
            return result;
        }
        String reason = remind == extend ? "平票（" + remind + " : " + extend + "），默认收手"
                : remind == 0 ? "没人投票，默认收手" : "提醒收手胜出（" + remind + " : " + extend + "），工资保留";
        return finishPlay(reason);
    }

    private Map<String, Object> interrupt(String action) {
        if (state.isPaused()) return result(false, "paused");
        if ("play".equals(action)) return beginPlay();
        if ("hide".equals(action)) return finishPlay("收到提醒");
        if (!"work".equals(state.getPhase())) return result(false, "phase:" + state.getPhase());
        if ("coffee".equals(action)) {
            if (!addRisk(COFFEE_RISK)) return result(false, "caught");
            setPhase("coffee", 2000);
            addFeed("system", "卡噗喝了一口咖啡，清醒了一点。", "");
            return result(true, null);
        }
        if ("peek".equals(action)) {
            setPhase("preview", 4500);
            addFeed("system", "卡噗把写到一半的页面投到屏幕上。", "");
            return result(true, null);
        }
        return result(false, "unknown-action");
    }

    private Snippet presentSnippet(String task, String title, String html, Map<String, Object> draw,
                                   String user, String reply) {
        long at = System.currentTimeMillis();
        Snippet snippet = new Snippet();
        snippet.setId(UUID.randomUUID().toString());
        snippet.setTask(task);
        snippet.setTitle(limit(title == null || title.isEmpty() ? (reply == null || reply.isEmpty() ? user : reply) : title, 24));
        snippet.setHtml(html);
        snippet.setDraw(draw);
        snippet.setUser(limit(user == null ? "观众" : user, 16));
        snippet.setReply(limit(reply == null ? "" : reply, 60));
        snippet.setAt(at);
        state.setSnippet(snippet);
        state.setLastTask(snippet);
        Map<String, Object> taskView = new LinkedHashMap<>();
        taskView.put("id", snippet.getId());
        taskView.put("user", snippet.getUser());
        taskView.put("task", task);
        taskView.put("reply", snippet.getReply());
        taskView.put("title", snippet.getTitle());
        taskView.put("at", at);
        state.getTasks().add(0, taskView);
        while (state.getTasks().size() > 10) state.getTasks().remove(state.getTasks().size() - 1);
        if ("work".equals(state.getPhase())) {
            setPhase("avatar".equals(task) ? "avatar" : "preview", "avatar".equals(task) ? 5500 : 4500);
        }
        return snippet;
    }

    public synchronized Map<String, Object> applyPreset(String preset, ActionEvent event) {
        Player player = getPlayer(event.getUser());
        if ("join".equals(preset)) {
            player.setScore(player.getScore() + 2);
            addFeed("join", "加入围观", player.getName());
            return result(true, null);
        }
        if ("like".equals(preset)) {
            int count = event.getCount() == null ? 1 : Math.max(1, Math.min(300, event.getCount()));
            player.setLikes(player.getLikes() + count);
            state.getStats().put("likes", state.getStats().getOrDefault("likes", 0) + count);
            addFeed("like", "点赞 ×" + count, player.getName());
            addRisk((int) Math.ceil(count / 20.0));
            Map<String, Object> result = result(true, null);
            result.put("liked", count);
            return result;
        }
        if ("gift".equals(preset)) {
            if (!"gift".equals(event.getType()) || !GAME_KEYS.contains(event.getGift())) return result(false, "invalid-gift");
            applyGift(player, event.getGift());
            return result(true, null);
        }
        if ("vote:1".equals(preset) || "vote:2".equals(preset)) {
            int choice = "vote:1".equals(preset) ? 1 : 2;
            Map<String, Object> vote = vote(player.getName(), choice);
            if (!Boolean.TRUE.equals(vote.get("ok")) && "vote-closed".equals(vote.get("reason"))) {
                addFeed("hint", "想投票，但现在没开投票", player.getName());
            }
            return vote;
        }
        return result(false, "unknown-preset");
    }

    private void applyGift(Player player, String gift) {
        Integer pay = PayrollService.giftPay(gift);
        payroll.record("gift", pay == null ? 0 : pay, player.getName() + " · " + gift);
        player.setGifts(player.getGifts() + 1);
        player.setScore(player.getScore() + giftScore(gift));
        if ("coffee".equals(gift)) {
            state.setRisk(Math.max(0, state.getRisk() - 15));
            addFeed("gift", "送来咖啡，摸鱼值 -15，小橘 +1", player.getName());
        } else if ("keyboard".equals(gift)) {
            addFeed("gift", "送来机械键盘，小橘 +5", player.getName());
        } else if ("monitor".equals(gift)) {
            if ("play".equals(state.getPhase()) && state.getVoteDeadline() != null) {
                state.setVoteDeadline(state.getVoteDeadline() + 5000);
                state.setPhaseEndsAt(state.getVoteDeadline());
                addFeed("gift", "送来显示器，本轮加时 5 秒，小橘 +3", player.getName());
            } else {
                addFeed("gift", "送来显示器，小橘 +3", player.getName());
            }
        } else if ("vacation".equals(gift)) {
            state.setRisk(0);
            addFeed("gift", "送来带薪假，摸鱼值清零，小橘 +10", player.getName());
        }
    }

    private int giftScore(String gift) {
        if ("coffee".equals(gift)) return 10;
        if ("keyboard".equals(gift)) return 30;
        if ("monitor".equals(gift)) return 60;
        return 120;
    }

    public synchronized Map<String, Object> applyJudge(ActionEvent event, JudgeDecision decision, Map<String, Object> meta) {
        Player player = getPlayer(event.getUser());
        player.setScore(player.getScore() + 2);
        state.setLastNarration(decision.getNarration());
        addFeed("ai", decision.getNarration(), "");

        Map<String, Object> effect = decision.getEffect();
        String kind = String.valueOf(effect.getOrDefault("kind", "noop"));
        Map<String, Object> result = result(true, null);
        if ("vote".equals(kind)) {
            Object voteValue = effect.get("vote");
            int choice = voteValue instanceof Number ? ((Number) voteValue).intValue() : 0;
            result = vote(player.getName(), choice);
            if (!Boolean.TRUE.equals(result.get("ok"))) addFeed("hint", "现在不是投票时间，判官的话先记下。", "");
        } else if ("risk".equals(kind)) {
            int delta = effect.get("delta") instanceof Number ? ((Number) effect.get("delta")).intValue() : 0;
            state.setRisk(Math.max(0, Math.min(RISK_LIMIT, state.getRisk() + delta)));
            if (state.getRisk() >= RISK_LIMIT) caught("判官一句话，摸鱼值拉满");
            addFeed("ai", "摸鱼值 " + (delta >= 0 ? "+" : "") + delta, "");
        } else if ("action".equals(kind)) {
            String action = String.valueOf(effect.getOrDefault("action", "none"));
            result = interrupt(action);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                addFeed("hint", "这个动作现在做不了（" + result.get("reason") + "）", "");
            }
        }
        if (Boolean.TRUE.equals(meta.get("degraded"))) {
            addFeed("warning", "判官降级：" + decision.getNarration(), "");
        }
        return result;
    }

    public synchronized Map<String, Object> applyTask(ActionEvent event, TaskDecision data, Map<String, Object> meta) {
        Player player = getPlayer(event.getUser());
        player.setScore(player.getScore() + 6);
        player.setTasks(player.getTasks() + 1);
        state.getStats().put("tasks", state.getStats().getOrDefault("tasks", 0) + 1);

        String requested = data != null && TASK_KINDS.contains(data.getTask()) ? data.getTask()
                : String.valueOf(meta.getOrDefault("taskKind", "ad"));
        String task = TASK_KINDS.contains(requested) ? requested : "ad";
        Game preset = "game".equals(task) ? assets.pickGame() : null;
        String reply = data != null && data.getReply() != null && !data.getReply().isEmpty()
                ? data.getReply()
                : preset != null ? "给你开了《" + preset.getTitle() + "》，点一下就能玩。"
                : Boolean.TRUE.equals(meta.get("degraded")) ? "卡噗一时没听清，先给你画一张。" : "卡噗接单了。";
        String title = preset != null ? preset.getTitle()
                : data != null && data.getTitle() != null && !data.getTitle().isEmpty() ? data.getTitle()
                : event.getCommand();
        String html = preset != null ? preset.getHtml()
                : data != null && data.getHtml() != null && !data.getHtml().isEmpty() ? data.getHtml()
                : assets.screen("ad");
        Map<String, Object> draw = "avatar".equals(task) && data != null ? data.getDraw() : null;
        if ("avatar".equals(task) && draw == null) draw = defaultAvatarDraw();
        presentSnippet(task, title, html, draw, player.getName(), reply);
        addFeed("task", "点单：" + reply, player.getName());
        if (preset != null) addFeed("game", "预置游戏：" + preset.getTitle(), player.getName());
        if (Boolean.TRUE.equals(meta.get("degraded")) && preset == null) {
            addFeed("warning", "点单降级：用预置小页面顶上", "");
        }
        Map<String, Object> result = result(true, null);
        result.put("snippet", state.getSnippet());
        return result;
    }

    @Scheduled(fixedDelay = 500)
    public synchronized void scheduledTick() {
        tick(System.currentTimeMillis());
    }

    private void tick(long at) {
        if (state.isPaused()) return;
        if ("pickup".equals(state.getPhase()) && state.getPhaseEndsAt() != null && at >= state.getPhaseEndsAt()) {
            openVote();
        } else if ("play".equals(state.getPhase()) && state.getVoteDeadline() != null && at >= state.getVoteDeadline()) {
            resolveVote(false);
        } else if (Arrays.asList("hide", "caught", "coffee", "preview", "avatar").contains(state.getPhase())
                && state.getPhaseEndsAt() != null && at >= state.getPhaseEndsAt()) {
            boolean wasCaught = "caught".equals(state.getPhase());
            if (wasCaught) settleInspection();
            else {
                setPhase("work", 0);
                restoreProjectScreen();
            }
            addFeed("system", wasCaught ? "本次查岗结束。" : "卡噗回到工位，继续写页面。", "");
        }
    }

    public synchronized Map<String, Object> control(String action) {
        if ("play".equals(action)) return beginPlay();
        if ("finish".equals(action)) return finishPlay("主播喊停");
        if ("resolve".equals(action)) {
            if ("caught".equals(state.getPhase())) {
                settleInspection();
                return result(true, null);
            }
            return resolveVote(true);
        }
        if ("inspect".equals(action)) {
            if ("play".equals(state.getPhase()) || "pickup".equals(state.getPhase())) finishPlay("查岗");
            caught("主播触发查岗");
            return result(true, null);
        }
        if ("preview".equals(action)) {
            if (!"work".equals(state.getPhase())) return result(false, "phase:" + state.getPhase());
            Snippet current = state.getSnippet();
            if (current == null || "work-screen".equals(current.getId())) return result(false, "no-work");
            setPhase("preview", 4500);
            addFeed("system", "卡噗把作品投到了屏幕上。", "");
            return result(true, null);
        }
        if ("avatar".equals(action)) {
            if (!"work".equals(state.getPhase())) return result(false, "phase:" + state.getPhase());
            presentSnippet("avatar", "演示头像", "", defaultAvatarDraw(), "主播", "给你画一个头像。");
            addFeed("system", "卡噗开始画头像。", "");
            return result(true, null);
        }
        if ("reset".equals(action)) {
            state = fresh();
            return result(true, null);
        }
        if ("pause".equals(action)) {
            long at = System.currentTimeMillis();
            if (!state.isPaused()) {
                state.setPaused(true);
                state.setPausedAt(at);
            } else {
                long delta = at - (state.getPausedAt() == null ? at : state.getPausedAt());
                if (state.getPhaseEndsAt() != null) state.setPhaseEndsAt(state.getPhaseEndsAt() + delta);
                if (state.getVoteDeadline() != null) state.setVoteDeadline(state.getVoteDeadline() + delta);
                state.setPaused(false);
                state.setPausedAt(null);
            }
            addFeed("system", state.isPaused() ? "直播暂停，倒计时冻结。" : "直播继续。", "");
            Map<String, Object> result = result(true, null);
            result.put("paused", state.isPaused());
            return result;
        }
        return result(false, "unknown-control");
    }

    public synchronized Map<String, Object> publicState() {
        long at = state.isPaused() && state.getPausedAt() != null ? state.getPausedAt() : System.currentTimeMillis();
        long timeLeft = 0;
        if ("play".equals(state.getPhase()) && state.getVoteDeadline() != null) {
            timeLeft = Math.max(0, (long) Math.ceil((state.getVoteDeadline() - at) / 1000.0));
        } else if (state.getPhaseEndsAt() != null) {
            timeLeft = Math.max(0, (long) Math.ceil((state.getPhaseEndsAt() - at) / 1000.0));
        }
        PayrollSummary payrollSummary = payroll.summary();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", state.getPhase());
        map.put("phaseLabel", state.getPhaseLabel());
        map.put("timeLeft", timeLeft);
        map.put("paused", state.isPaused());
        map.put("round", state.getRound());
        map.put("salary", Math.round(payrollSummary.getActual() * 100));
        map.put("payroll", payrollSummary);
        map.put("risk", state.getRisk());
        map.put("extended", state.isExtended());
        map.put("votes", state.getVotes());
        map.put("votersCount", state.getVoters().size());
        List<Map<String, Object>> voters = new ArrayList<>();
        state.getVoters().forEach((name, choice) -> {
            Map<String, Object> voter = new LinkedHashMap<>();
            voter.put("name", name);
            voter.put("choice", choice);
            voters.add(voter);
        });
        map.put("voterList", voters);
        List<Player> players = new ArrayList<>(state.getPlayers().values());
        players.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        map.put("players", players);
        map.put("feed", state.getFeed().size() > 30 ? state.getFeed().subList(0, 30) : state.getFeed());
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("mode", state.getAiMode());
        ai.put("lastNarration", state.getLastNarration());
        ai.put("tasks", state.getTasks());
        ai.put("snippet", state.getSnippet());
        ai.put("lastTask", state.getLastTask());
        Map<String, Object> config = deepSeek.config();
        ai.put("paused", config.get("paused"));
        ai.put("hasKey", config.get("hasKey"));
        ai.put("keyFingerprint", config.get("keyFingerprint"));
        ai.put("model", config.get("model"));
        ai.put("thinking", config.get("thinking"));
        ai.put("background", background.status());
        ai.put("tasks", state.getTasks());
        map.put("ai", ai);
        map.put("currentGame", state.getCurrentGame());
        map.put("project", state.getProject());
        map.put("lastResult", state.getLastResult());
        map.put("stats", state.getStats());
        map.put("usage", ledger.summary());
        return map;
    }

    private Map<String, Object> defaultAvatarDraw() {
        Map<String, Object> draw = new LinkedHashMap<>();
        draw.put("bg", "#20332f");
        List<Map<String, Object>> shapes = new ArrayList<>();
        shapes.add(shape("circle", "cx", 100, "cy", 78, "r", 46, "fill", "#e8b98d"));
        shapes.add(shape("circle", "cx", 54, "cy", 82, "r", 11, "fill", "#e8b98d"));
        shapes.add(shape("circle", "cx", 146, "cy", 82, "r", 11, "fill", "#e8b98d"));
        shapes.add(shape("rect", "x", 60, "y", 124, "w", 80, "h", 58, "rx", 20, "fill", "#466747"));
        shapes.add(shape("line", "x1", 70, "y1", 62, "x2", 90, "y2", 60, "stroke", "#3a2a1e", "width", 5));
        shapes.add(shape("line", "x1", 110, "y1", 60, "x2", 130, "y2", 62, "stroke", "#3a2a1e", "width", 5));
        shapes.add(shape("circle", "cx", 82, "cy", 84, "r", 5, "fill", "#2b2118"));
        shapes.add(shape("circle", "cx", 118, "cy", 84, "r", 5, "fill", "#2b2118"));
        shapes.add(shape("arc", "cx", 100, "cy", 98, "r", 14, "start", 0.3, "end", 2.8, "stroke", "#7c4a3a", "width", 3));
        draw.put("shapes", shapes);
        return draw;
    }

    private Map<String, Object> shape(String type, Object... values) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("type", type);
        for (int i = 0; i + 1 < values.length; i += 2) {
            shape.put(String.valueOf(values[i]), values[i + 1]);
        }
        return shape;
    }

    public synchronized Snippet snippetForScreen(String id, String mode) {
        if (id == null || id.isEmpty() || mode == null || mode.isEmpty()) return null;
        if (!mode.equals(state.getPhase())) return null;
        Snippet snippet = state.getSnippet();
        if (snippet == null || !id.equals(snippet.getId())) return null;
        if ("preview".equals(mode)) return snippet;
        if ("play".equals(mode) && "game".equals(snippet.getTask())) return snippet;
        return null;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phase", state.getPhase());
        map.put("phaseLabel", state.getPhaseLabel());
        map.put("round", state.getRound());
        map.put("salary", Math.round(payroll.summary().getActual() * 100));
        map.put("risk", state.getRisk());
        map.put("extended", state.isExtended());
        Map<String, Object> voteView = new LinkedHashMap<>();
        voteView.put("收手", state.getVotes().getOrDefault("1", 0));
        voteView.put("再玩", state.getVotes().getOrDefault("2", 0));
        map.put("votes", voteView);
        map.put("voters", state.getVoters().size());
        map.put("lastNarration", state.getLastNarration());
        List<String> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(6, state.getFeed().size()); i++) {
            FeedItem item = state.getFeed().get(i);
            recent.add((item.getUser() == null || item.getUser().isEmpty() ? "" : item.getUser() + "：") + item.getText());
        }
        map.put("recent", recent);
        return map;
    }

    private Map<String, Object> result(boolean ok, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", ok);
        if (reason != null) {
            map.put("reason", reason);
            map.put("error", reason);
        }
        return map;
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public synchronized String getPhase() {
        return state.getPhase();
    }
}
