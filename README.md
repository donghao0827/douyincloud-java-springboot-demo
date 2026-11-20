# 卡噗的办公室 · 抖音云服务端（Spring Boot）

这是《卡噗的办公室》弹幕玩法的抖音云服务端，基于 [bytedance/douyincloud-java-springboot-demo](https://github.com/bytedance/douyincloud-java-springboot-demo) 的 `feature/liveplay` 分支，把原来本地 Node 服务里的游戏状态机、DeepSeek 裁决、token 台账、工资和小游戏搬到了 Spring Boot。

## 已实现

- 抖音弹幕玩法事件：`/start_game` 开启评论/点赞/礼物/粉丝团/进场推送，`/live_data_callback` 接收并映射到游戏事件。
- 游戏状态机：上班、拿掌机、摸鱼投票、收手、查岗表决、咖啡、预览、头像；随机预置小游戏（接橘子 / 拍老板 / 翻牌记忆）。
- 四层 agent 的核心三层：`router` 分诊、`judge` 判官、`task` 点单；游戏点单直接走预置，不再让模型现写。
- 真实 DeepSeek 调用：`deepseek-flash`、关闭思考、超时/重试/降级；`AI_PAUSED` 总开关。
- 真实 token 台账：`data/usage.jsonl`，按 session/today/allTime/agent 拆分；工资按月底薪 + 礼物 − 查岗 − 模型自费计算。
- REST/SSE：`/api/state`、`/api/events`、`/api/action`、`/api/control`、`/api/usage`、`/api/usage/balance`、`/api/ai/pause`、`/api/health`。
- 控制接口可选 `API_TOKEN` 保护，防止公网任意触发互动烧 token。

## 本地运行

需要 JDK 8 和 Maven（仓库的 `mvnw` 没有带 wrapper jar，直接用本机 `mvn` 或 Docker）。

```bash
export JAVA_HOME=/path/to/jdk8
export DEEPSEEK_API_KEY=sk-你的专用AK
export AI_PAUSED=true          # 本地先不调模型时可以设 true
mvn -s settings.xml -DskipTests package
PORT=8000 java -jar target/douyincloud-demo-0.0.1-SNAPSHOT.jar
```

- 状态：<http://127.0.0.1:8000/api/state>
- 用量：<http://127.0.0.1:8000/api/usage>
- 健康：<http://127.0.0.1:8000/api/health>

本地模拟一局：

```bash
curl -X POST http://127.0.0.1:8000/api/control \
  -H 'content-type: application/json' -d '{"action":"play"}'

curl -X POST http://127.0.0.1:8000/live_data_callback \
  -H 'content-type: application/json' -H 'x-msg-type: live_comment' \
  -d '[{"msg_id":"m1","data":{"content":"老板来了快收手","userInfo":{"userName":"小橘"}}}]'
```

## 环境变量

| 变量 | 说明 |
| --- | --- |
| `PORT` | 服务端口，默认 8000 |
| `DEEPSEEK_API_KEY` | 专用 AK；不填则全部走本地降级，token 恒为 0 |
| `DEEPSEEK_BASE_URL` | 默认 `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | 默认 `deepseek-flash` |
| `DEEPSEEK_TIMEOUT_MS` | 默认 8000 |
| `DEEPSEEK_THINKING` | 默认 false |
| `AI_PAUSED` | `true` 时所有模型调用直接跳过 |
| `API_TOKEN` | 设置后 `/api/action`、`/api/control`、`/api/ai/pause` 需要 `X-Kapu-Token` 请求头 |
| `DATA_DIR` | 台账/工资文件目录，默认 `data` |

## 抖音云部署

1. 把这个仓库推到你的 Git 仓库（不要推到 bytedance 官方仓库）。
2. 在抖音云控制台创建/进入小玩法应用，选择「Git 代码部署」，分支选你的分支，构建用仓库里的 `Dockerfile`。
3. 服务端口填 **8000**（`application.yml` 默认值）。
4. 在环境变量里配置 `DEEPSEEK_API_KEY`、`AI_PAUSED=false`、`API_TOKEN=随机长串` 等。
5. 在直播小玩法后台配置：
   - 开始玩法回调：`POST /start_game`
   - 结束玩法回调：`POST /finish_game`
   - 直播数据回调：`POST /live_data_callback`
6. 用真实 AppID/直播间开播，确认日志出现 `[liveplay] callback ... events=N`。

`/start_game` 会按 demo 的方式调用抖音云内网接口开启 `live_like / live_comment / live_gift / live_fansclub / live_inout` 推送；`/live_data_callback` 会按 `x-msg-type` 和 payload 自动映射。

## 事件格式兼容

目前兼容两类：

1. `x-msg-type` + `data` 对象（官方 demo / 常见回调）
2. `{ "msg_type": "...", "data": "json string", "timestamp": ... }` 包装格式

用户字段兼容 `userInfo.userName`、`user.nickname`、`nickName` 等；礼物兼容 `giftName`、`gift.name`，并按名称映射到 `coffee / keyboard / monitor / vacation`。如果真实回调字段不同，服务端会打印 `[liveplay] raw=...`，按日志补映射即可。

## 已知边界

- **常驻内容工坊 agent 未移植**：它是在本地写 `workspace/artifacts` 的开发期工具，不属于直播运行链路；需要的话可以再加一个定时任务。
- **`/screen.jpg` 浏览器截图服务未移植**：抖音云 Java 容器里没有 Puppeteer；卡片端对小游戏已做原生 Canvas 兜底。头像/广告的渲染预览如果还要，需要单独的浏览器渲染服务或改卡片原生渲染。
- **数据是容器本地文件**：`data/usage.jsonl`、`data/payroll.jsonl` 在云上默认是临时的；要跨重启累计需要挂载持久化存储或接数据库。
- **网络**：抖音云回调走内网专线域名；本地开发无法验证 `/start_game`，但 `/live_data_callback` 可以用上面的 curl 模拟。
