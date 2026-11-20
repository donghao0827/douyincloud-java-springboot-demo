const $ = (selector) => document.querySelector(selector);
const get = (id) => document.getElementById(id);

let autoSim = null;
let lastSnippetId = null;
let lastState = null;

function fitMiniScreen() {
  const frame = get("snippetFrame");
  if (!frame) return;
  const box = frame.parentElement;
  const width = box.clientWidth;
  const height = box.clientHeight;
  if (!width || !height) return;
  const scale = Math.min(width / 440, height / 260);
  frame.style.transform = `scale(${scale})`;
  frame.style.left = `${(width - 440 * scale) / 2}px`;
  frame.style.top = `${(height - 260 * scale) / 2}px`;
}

if (window.ResizeObserver) {
  new ResizeObserver(fitMiniScreen).observe(get("snippetFrame").parentElement);
}
window.addEventListener("resize", fitMiniScreen);
setTimeout(fitMiniScreen, 0);

const tokenInput = get("apiToken");
if (tokenInput) {
  tokenInput.value = localStorage.getItem("kapuToken") || "";
  tokenInput.addEventListener("input", () => localStorage.setItem("kapuToken", tokenInput.value.trim()));
}

const CSP = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:; media-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-src 'none'"><meta charset="utf-8">`;


function avatarCanvasDocument(draw) {
  const data = JSON.stringify(draw).replace(/</g, "\\u003c");
  return `<style>html,body{margin:0;width:440px;height:260px;overflow:hidden;background:#182722}canvas{display:block}</style>
  <canvas id="avatar" width="440" height="260"></canvas>
  <script>
  const draw = ${data};
  const canvas = document.getElementById('avatar');
  const ctx = canvas.getContext('2d');
  const S = 260 / 200, OX = 90, OY = 0;
  const started = performance.now();
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  function rounded(x, y, w, h, r) {
    r = Math.min(r || 0, w / 2, h / 2);
    ctx.beginPath(); ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r); ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y);
  }
  function paint(s) {
    if (s.fill) { ctx.fillStyle = s.fill; ctx.fill(); }
    if (s.stroke) { ctx.strokeStyle = s.stroke; ctx.lineWidth = Math.max(1, (s.width || 2) * S); ctx.stroke(); }
  }
  function shape(s) {
    const X = v => OX + v * S, Y = v => OY + v * S, R = v => v * S;
    if (s.type === 'circle') { ctx.beginPath(); ctx.arc(X(s.cx), Y(s.cy), R(s.r), 0, Math.PI * 2); paint(s); }
    else if (s.type === 'rect') { rounded(X(s.x), Y(s.y), s.w * S, s.h * S, (s.rx || 0) * S); paint(s); }
    else if (s.type === 'polygon' && s.points && s.points.length) {
      ctx.beginPath(); s.points.forEach((p, i) => i ? ctx.lineTo(X(p[0]), Y(p[1])) : ctx.moveTo(X(p[0]), Y(p[1])));
      ctx.closePath(); paint(s);
    } else if (s.type === 'line') {
      ctx.beginPath(); ctx.moveTo(X(s.x1), Y(s.y1)); ctx.lineTo(X(s.x2), Y(s.y2)); paint(s);
    } else if (s.type === 'arc') {
      ctx.beginPath(); ctx.arc(X(s.cx), Y(s.cy), R(s.r), s.start, s.end); paint(s);
    }
  }
  function frame(now) {
    ctx.clearRect(0, 0, 440, 260);
    ctx.fillStyle = /^#[0-9a-f]{6}$/i.test(draw.bg || '') ? draw.bg : '#182722';
    ctx.fillRect(0, 0, 440, 260);
    const shapes = draw.shapes || [];
    const shown = Math.min(shapes.length, 1 + Math.floor((now - started) / 170));
    for (let i = 0; i < shown; i++) shape(shapes[i]);
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  </script>`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function authHeader() {
  const token = get("apiToken")?.value?.trim() || localStorage.getItem("kapuToken") || "";
  return token ? { "X-Kapu-Token": token } : {};
}

async function post(path, body) {
  try {
    const response = await fetch(path, {
      method: "POST",
      headers: { "content-type": "application/json", ...authHeader() },
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${response.status}`);
    }
    return response.json().catch(() => ({}));
  } catch (error) {
    console.error("[control]", path, error);
    const message = String(error.message || "请求失败");
    get("conn").textContent = `请求失败：${message}`;
    get("conn").classList.remove("on");
    const el = get("controlMessage");
    if (el) {
      el.textContent = /401/.test(message)
        ? "需要主播口令：在「对局控制」填写 API_TOKEN 后再点"
        : `请求失败：${message}`;
    }
    return null;
  }
}

function user() {
  return get("userName").value.trim().slice(0, 16) || "观众";
}

function sendEvent(event) {
  return post("/api/action", { user: user(), ...event });
}

function randomUser() {
  const names = ["小橘", "阿禾", "元宝", "小满", "长风", "阿宁", "洛洛", "青禾", "小陶", "燕七"];
  return `${names[Math.floor(Math.random() * names.length)]}${Math.ceil(Math.random() * 99)}`;
}

/* ------------------------- 观众模拟 ------------------------- */

function startAutoSim() {
  if (autoSim) {
    clearInterval(autoSim);
    autoSim = null;
    get("autoSim").textContent = "自动模拟观众";
    return;
  }
  get("autoSim").textContent = "停止自动模拟";
  autoSim = setInterval(() => {
    const name = randomUser();
    const roll = Math.random();
    if (roll < 0.42) {
      sendEvent({ type: "comment", user: name, command: String(1 + Math.floor(Math.random() * 2)) });
    } else if (roll < 0.62) {
      sendEvent({ type: "like", user: name, count: 5 + Math.floor(Math.random() * 45) });
    } else if (roll < 0.76) {
      const gifts = ["coffee", "keyboard", "monitor", "vacation"];
      sendEvent({ type: "gift", user: name, gift: gifts[Math.floor(Math.random() * gifts.length)] });
    } else if (roll < 0.86) {
      sendEvent({ type: "comment", user: name, command: "加入" });
    } else {
      const comments = [
        "老板来了，快收手",
        "再玩五秒嘛，就五秒",
        "卡噗你写的这个页面挺好看",
        "帮我做个咖啡广告页，要橘子味的",
        "画个戴眼镜的水豚头像",
        "来个接橘子的小游戏",
        "列一下卡噗今天的待办",
        "你这摸鱼值快满了吧",
      ];
      sendEvent({ type: "comment", user: name, command: comments[Math.floor(Math.random() * comments.length)] });
    }
  }, 1500);
}

document.addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  if (button.dataset.control) {
    const action = button.dataset.control;
    post("/api/control", { action }).then((data) => {
      const el = get("controlMessage");
      if (!el) return;
      if (!data) { el.textContent = "请求失败，检查主播口令或服务连接"; return; }
      if (data.ok === false) {
        const reason = data.reason || data.error || "";
        el.textContent = reason === "no-work" ? "还没有作品，先点单生成一个页面"
          : reason.startsWith("phase:") ? `当前阶段 ${reason.slice(6)}，等回到 work 再试`
          : reason === "unknown-control" ? "服务端还是旧版本，重新部署最新代码"
          : `操作未完成：${reason || "未知原因"}`;
        return;
      }
      el.textContent = action === "preview" ? "已触发展示作品" : action === "avatar" ? "已开始画头像" : "已触发";
    });
  }
  if (button.dataset.event === "join") sendEvent({ type: "join" });
  if (button.dataset.command) sendEvent({ type: "comment", command: button.dataset.command });
  if (button.dataset.like) sendEvent({ type: "like", count: Number(button.dataset.like) });
  if (button.dataset.gift) sendEvent({ type: "gift", gift: button.dataset.gift });
  if (button.id === "autoSim") startAutoSim();
  if (button.id === "balanceButton") refreshBalance();
  if (button.id === "workStep") runWorkStep();
  if (button.id === "workToggle") toggleWorkAgent();
  if (button.id === "aiPause") toggleAiPause();
});

get("sendComment").addEventListener("click", () => {
  const input = get("commentInput");
  const command = input.value.trim();
  if (!command) return;
  sendEvent({ type: "comment", command });
  input.value = "";
});

get("commentInput").addEventListener("keydown", (event) => {
  if (event.key === "Enter") get("sendComment").click();
});

get("sendOrder").addEventListener("click", () => {
  const kind = get("orderKind").value;
  const desc = get("orderText").value.trim();
  const label = { ad: "咖啡广告页", avatar: "水豚头像", game: "小游戏", todo: "待办清单", code: "代码" }[kind];
  const command = desc ? `帮我做个${label}：${desc}` : `帮我做个${label}`;
  sendEvent({ type: "comment", command });
  get("orderText").value = "";
});

/* ------------------------- 对局渲染 ------------------------- */

function formatTimer(seconds) {
  return seconds > 0 ? `${seconds} 秒` : "--";
}

function resultNote(result) {
  if (!result) return "";
  if (result.kind === "extend") return `上一轮：再玩五秒胜出（${result.extend} : ${result.remind}）`;
  if (result.kind === "caught") return result.loss ? "查岗结算 · 扣 1 颗小橘" : "查岗结算 · 本次免扣";
  if (result.kind === "finish") return `本轮结果：收手 ${result.remind} · 再玩 ${result.extend}${result.extended ? " · 已延时" : ""}`;
  return "";
}

function renderVotes(state) {
  const inspection = state.phase === "caught";
  const labels = inspection ? ["扣薪", "求情"] : ["提醒收手", "再玩五秒"];
  for (const btn of document.querySelectorAll("[data-command]")) {
    const option = Number(btn.dataset.command);
    if (option === 1 || option === 2) btn.textContent = `评论 ${option}：${labels[option - 1]}`;
  }
  const total = state.votes[1] + state.votes[2];
  get("voteNote").textContent = inspection ? `查岗表决：扣 1 颗小橘或免扣 · 剩 ${state.timeLeft} 秒` : state.phase === "play"
    ? `第 ${state.round} 轮 · 还剩 ${state.timeLeft} 秒${state.extended ? " · 已延长 5 秒" : ""}`
    : state.phase === "pickup"
      ? "卡噗已拿起掌机，摸鱼投票已开始。"
      : resultNote(state.lastResult) || "还没开始摸鱼。";

  const bar = (label, count, cls) => {
    const percent = total ? Math.round((count / total) * 100) : 0;
    return `<div><div class="bar-head"><span>${label}</span><b>${count} 票 · ${percent}%</b></div><div class="track ${cls}"><i style="width:${percent}%"></i></div></div>`;
  };
  get("voteBars").innerHTML = bar("1 · " + labels[0], state.votes[1], "remind") + bar("2 · " + labels[1], state.votes[2], "extend");

  const voters = state.voterList || [];
  get("voters").innerHTML = voters.length
    ? voters
      .map((item) => `<span>${escapeHtml(item.name)} · ${labels[item.choice - 1]}</span>`)
      .join("")
    : `<span>还没有人投票</span>`;
}

function applySnippet(state) {
  const snippet = state.ai?.snippet;
  if (!snippet) {
    get("snippetNote").textContent = "显示器里是卡噗的工位脚本。";
    return;
  }
  if (snippet.id !== lastSnippetId) {
    lastSnippetId = snippet.id;
    const draw = snippet.task === "avatar" && snippet.draw && snippet.draw.shapes;
    get("snippetFrame").srcdoc = draw
      ? `${CSP}${avatarCanvasDocument(snippet.draw)}`
      : `${CSP}${snippet.html || ""}`;
  }
  get("snippetNote").textContent = `${snippet.title || snippet.reply || ""}${snippet.user ? ` · ${snippet.user}` : ""}`;

  const tasks = state.ai.tasks || [];
  get("taskList").innerHTML = tasks.length
    ? tasks
      .slice(0, 5)
      .map(
        (task) => `<li><b>${escapeHtml(task.user)}</b> · ${escapeHtml(task.title || task.reply || "")}
          <small>${escapeHtml(task.reply || "")}</small></li>`,
      )
      .join("")
    : `<li><small>还没有点单。观众说“帮我画个头像”会进入专职 agent。</small></li>`;
}

function renderAi(state) {
  const ai = state.ai || {};
  const mode = ai.paused
    ? "AI 已暂停"
    : {
      llm: "大模型裁决",
      fallback: "降级规则",
      no_key: "未配置 AK",
      idle: "待命",
    }[ai.mode] || ai.mode || "待命";
  const last = ai.last
    ? `${ai.last.route || "—"}${ai.last.latencyMs ? ` · ${ai.last.latencyMs}ms` : ""}${ai.last.preset ? ` · ${ai.last.preset}` : ""}${ai.last.task ? ` · ${ai.last.task}` : ""}${ai.last.degraded ? " · 降级" : ""}`
    : "—";
  const rows = [
    ["模式", mode, ai.paused ? "warn" : ai.mode === "llm" ? "ok" : "warn"],
    ["AI 总开关", ai.paused ? "已暂停（本地降级）" : "运行中", ai.paused ? "warn" : "ok"],
    ["单飞状态", ai.busy ? `处理中${ai.pending ? " · 有 1 条待处理" : ""}` : "空闲"],
    ["已处理 / 丢弃", `${ai.processed || 0} / ${ai.dropped || 0}`],
    ["上一条", last],
    ["模型", ai.model || "—"],
    ["AK", ai.hasKey ? ai.keyFingerprint : "未配置"],
  ];
  get("aiPause").textContent = ai.paused ? "恢复 AI 调用" : "暂停 AI 调用";
  get("aiStatus").innerHTML = rows
    .map(([label, value, cls]) => `<div class="kv-row"><span>${label}</span><b class="${cls || ""}">${escapeHtml(value)}</b></div>`)
    .join("");
}

function renderWork(background, aiPaused = false) {
  if (!background) {
    get("workStatus").innerHTML = `<div class="kv-row"><span>状态</span><b class="warn">未启动</b></div>`;
    get("workNote").textContent = "常驻 agent 暂不可用。";
    get("workStep").disabled = Boolean(aiPaused);
    get("workToggle").disabled = Boolean(aiPaused);
    return;
  }
  const stateText = aiPaused && background.state === "off"
    ? "已暂停（AI 总开关）"
    : {
      on: "上班中",
      off: "已下班",
      no_key: "没有 AK，无法开工",
      error: "出错待命",
    }[background.state] || background.state;
  const days = (background.schedule?.days || []).join("/");
  const rows = [
    ["状态", stateText, background.state === "on" ? "ok" : "warn"],
    ["工时", `${background.schedule?.start || ""}-${background.schedule?.end || ""} · 周 ${days}`],
    ["今日 / 累计", `${background.stepsToday || 0} / ${background.totalSteps || 0} 步`],
    ["上次产出", background.lastAt ? new Date(background.lastAt).toLocaleTimeString("zh-CN", { hour12: false }) : "—"],
  ];
  get("workStatus").innerHTML = rows
    .map(([label, value, cls]) => `<div class="kv-row"><span>${label}</span><b class="${cls || ""}">${escapeHtml(value)}</b></div>`)
    .join("");

  const parts = [];
  if (background.lastFile) parts.push(`文件：${background.lastFile}`);
  if (background.lastSummary) parts.push(`摘要：${background.lastSummary}`);
  if (background.lastNext) parts.push(`下一步：${background.lastNext}`);
  if (background.lastError) parts.push(`错误：${background.lastError}`);
  if (background.schedule?.workspace) parts.push(`空间：${background.schedule.workspace}`);
  if (aiPaused) parts.push("恢复 AI 调用后，工坊会按当前时间重新启动");
  get("workNote").textContent = parts.join(" ｜ ");
  get("workToggle").textContent = background.state === "on" ? "停止 agent" : "启动 agent";
  get("workStep").disabled = Boolean(aiPaused);
  get("workToggle").disabled = Boolean(aiPaused);
}

const FEED_LABELS = {
  system: "系统",
  join: "加入",
  like: "点赞",
  gift: "礼物",
  vote: "投票",
  ai: "判官",
  task: "点单",
  warning: "降级",
  hint: "提示",
  background: "工坊",
  game: "游戏",
};

function renderFeed(feed) {
  get("feed").innerHTML = (feed || [])
    .slice(0, 12)
    .map(
      (item) => `<p><span>${escapeHtml(item.user || FEED_LABELS[item.kind] || item.kind)}</span>${escapeHtml(item.text)}</p>`,
    )
    .join("");
}

function render(state) {
  lastState = state;
  if (state.payroll) {
    const p = state.payroll;
    get("payBreakdown").textContent = `${p.month} · 底薪 ${p.base} ＋ 礼物 ${p.gifts} − 查岗 ${p.fines} − 模型自费 ${p.cost.toFixed(4)} = ${p.actual.toFixed(2)} 小橘`;
    get("payEntries").replaceChildren(...p.recent.map(row => {
      const li = document.createElement("li");
      li.textContent = new Date(row.at).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai"}) + " · " + row.detail + " · " + (row.amount > 0 ? "+" : "") + row.amount;
      return li;
    }));
  }
  get("conn").classList.add("on");
  get("conn").textContent = "已连接";
  get("controlPhase").textContent = state.phaseLabel;
  get("controlTimer").textContent = formatTimer(state.timeLeft);
  get("playerCount").textContent = state.players.length;
  get("salaryRisk").textContent = `${state.salary < 0 ? "倒欠公司 " : ""}${(Math.abs(state.salary) / 100).toLocaleString("zh-CN")} 小橘 / ${state.risk}`;
  get("roundText").textContent = state.round > 0 ? `第 ${state.round} 轮` : "0";
  get("pauseButton").textContent = state.paused ? "继续倒计时" : "暂停倒计时";
  renderVotes(state);
  applySnippet(state);
  renderAi(state);
  renderWork(state.ai.background, state.ai.paused);
  renderFeed(state.feed);
}

/* ------------------------- 用量 ------------------------- */

const nf = new Intl.NumberFormat("zh-CN");
const num = (value) => nf.format(Number(value) || 0);
const cny = (value) => `¥${(Number(value) || 0).toFixed(6)}`;

function usageRow(label, view) {
  return `<div class="usage-row"><span>${label}</span><b>${num(view.calls)} 次 · ${num(view.totalTokens)} tok</b><em>${cny(view.costCny)}</em></div>`;
}

function renderUsage(data) {
  const cfg = data.config || {};
  const s = data.usage?.session || {};
  const t = data.usage?.today || {};
  const a = data.usage?.allTime || {};
  const rt = data.runtime || {};

  get("usageKey").textContent = cfg.hasKey
    ? `AK ${cfg.keyFingerprint} · ${cfg.model} · 思考模式${cfg.thinking ? "开" : "关"} · ${cfg.baseUrl}`
    : "⚠ 未配置 AK：当前全部走本地降级规则，不产生任何 token 消耗";

  get("usageGrid").innerHTML = `
    ${usageRow("本次会话", s)}
    ${usageRow("今日", t)}
    ${usageRow("累计", a)}
    <div class="usage-row"><span>提示 / 补全</span><b>${num(s.promptTokens)} / ${num(s.completionTokens)}</b><em>tok</em></div>
    <div class="usage-row"><span>缓存命中 / 未命中</span><b>${num(s.cacheHitTokens)} / ${num(s.cacheMissTokens)}</b><em>tok</em></div>
    <div class="usage-row"><span>推理 tokens</span><b>${num(s.reasoningTokens)}</b><em>tok</em></div>
    <div class="usage-row"><span>失败 / 平均延迟</span><b>${num(s.failures)} 次 / ${num(s.avgLatencyMs)} ms</b><em></em></div>`;

  const agents = Object.entries(data.usage?.byAgent || {});
  get("usageAgents").innerHTML = agents
    .map(([name, view]) => `<span class="agent">${escapeHtml(name)} <b>${num(view.totalTokens)}</b> <em>${cny(view.costCny)}</em></span>`)
    .join("");

  get("usageNote").textContent = `当前接入：${
    cfg.paused ? "AI 已暂停（本地降级）" : rt.mode === "llm" ? "大模型裁决" : rt.mode === "no_key" ? "未配置 AK" : "降级规则"
  } · 已处理 ${num(rt.processed)} 条 · 丢弃 ${num(rt.dropped)} 条 · 流水 data/usage.jsonl。`;
}

async function refreshUsage() {
  try {
    const response = await fetch("/api/usage", { cache: "no-store" });
    if (!response.ok) return;
    renderUsage(await response.json());
  } catch {
    get("usageKey").textContent = "用量接口暂时不可用";
  }
}

async function refreshBalance() {
  get("balanceText").textContent = "查询中…";
  try {
    const response = await fetch("/api/usage/balance", { cache: "no-store" });
    const data = await response.json();
    if (!data.ok) {
      get("balanceText").textContent = `余额查询失败：${data.error || "未知错误"}`;
      return;
    }
    const infos = (data.data?.balance_infos || [])
      .map((item) => `${item.currency} ${item.total_balance}（赠送 ${item.granted_balance} / 充值 ${item.topped_up_balance}）`)
      .join("；");
    get("balanceText").textContent = `账户余额：${infos || "无数据"}${data.data?.is_available === false ? "（不可用）" : ""}`;
  } catch (error) {
    get("balanceText").textContent = `余额查询失败：${error.message}`;
  }
}

/* ------------------------- 常驻 agent ------------------------- */

async function runWorkStep() {
  const button = get("workStep");
  button.disabled = true;
  button.textContent = "干活中…";
  try {
    const response = await fetch("/api/agent/step", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ force: true }),
    });
    const data = await response.json();
    if (data.error) get("workNote").textContent = `这一步没成：${data.error}`;
    else if (data.file) get("workNote").textContent = `产出：${data.file}`;
    else if (data.skipped) get("workNote").textContent = `跳过：${data.skipped}`;
  } catch (error) {
    get("workNote").textContent = `触发失败：${error.message}`;
  } finally {
    button.disabled = false;
    button.textContent = "立即干一步";
    refreshUsage();
  }
}

async function toggleAiPause() {
  const paused = !(lastState?.ai?.paused);
  try {
    await fetch("/api/ai/pause", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ paused }),
    });
  } catch (error) {
    get("workNote").textContent = `切换 AI 失败：${error.message}`;
  }
}

async function toggleWorkAgent() {
  const start = !(lastState?.ai?.background?.state === "on");
  try {
    await fetch("/api/agent/toggle", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ start }),
    });
  } catch (error) {
    get("workNote").textContent = `切换失败：${error.message}`;
  }
}

/* ------------------------- 启动 ------------------------- */

const snapshotMode = new URLSearchParams(location.search).has("snapshot");

if (snapshotMode) {
  fetch("/api/state")
    .then((response) => response.json())
    .then(render)
    .catch(() => {
      get("conn").classList.remove("on");
      get("conn").textContent = "读取失败";
    });
  refreshUsage();
} else {
  const stream = new EventSource("/api/events");
  stream.onopen = () => {
    get("conn").classList.add("on");
    get("conn").textContent = "已连接";
  };
  stream.onmessage = (event) => {
    try {
      render(JSON.parse(event.data));
    } catch (error) {
      console.error("[sse]", error);
    }
  };
  stream.onerror = () => {
    get("conn").classList.remove("on");
    get("conn").textContent = "连接中…";
  };

  refreshUsage();
  setInterval(refreshUsage, 2500);
}
