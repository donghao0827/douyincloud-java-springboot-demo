const { CLOUD, API_BASE, SCREEN_BASE } = require('../../config');
const statePath = '/api/state';
function encodeFrame(buffer) {
  const bytes = new Uint8Array(buffer);
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let result = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | (bytes[i+1] << 8) | bytes[i+2];
    result += alphabet[n >>> 18] + alphabet[(n >>> 12) & 63] +
      (i+1 < bytes.length ? alphabet[(n >>> 6) & 63] : '=') +
      (i+2 < bytes.length ? alphabet[n & 63] : '=');
  }
  return result;
}
Page({
  data: { controlsReady:false, saving:false, aiOn:false, autoOn:false, agentBusy:false,
    intervalMinutes:0, intervals:[5,15,30], operatorEntered:false, controlMessage:'',
    todayTokens:'—', todayCost:'—', status: '正在启动卡片', selected: '', bossThreshold: { min: 10, max: 30 }, bossCountdown: 0, actions: [
    {phase:'pickup',label:'拿掌机'}, {phase:'play',label:'摸鱼投票'}, {phase:'inspect',label:'老板查岗'},
    {phase:'coffee',label:'喝咖啡'}, {phase:'preview',label:'展示作品'}, {phase:'avatar',label:'画头像'}
  ] },
  onLoad() {
    this.active = true;
    this.screenKey = '';
    tt.getLiveRoomCardInfo({
      success: ({ liveCardMaxWidth, liveCardMaxHeight }) => {
        const width = Math.floor(liveCardMaxWidth);
        const height = Math.floor(liveCardMaxHeight);
        if (!(width > 0 && height > 0)) return this.setData({status:'卡片空间不可用'});
        tt.createLiveCard({
          url:'/live-card/game', width, height,
          success: ({cardContext}) => {
            this.card = cardContext;
            this.viewport = {width, height};
            this.card.receive({viewport: this.viewport});
            console.info('卡噗工位已创建',width,height);
            this.sync();
          },
          fail: err => this.setData({status:err.errMsg}),
        });
      },
      fail: err => this.setData({status:err.errMsg}),
    });
  },
  sync() {
    if (!this.active) return;
    const callbacks = {
      success: ({data,statusCode}) => {
        if (!this.active) return;
        if (typeof data === 'string') {
          try { data = JSON.parse(data); } catch { data = null; }
        }
        if(statusCode!==200 || !data || !data.phase) return this.setData({status:'工资服务未返回玩法状态'});
        this.liveState = data;
        const ai = data.ai || {}, agent = ai.background || {}, today = data.usage && data.usage.today;
        this.setData({
          controlsReady:typeof agent.autoEnabled === 'boolean' && typeof ai.paused === 'boolean',
          aiOn:ai.paused === false,autoOn:agent.autoEnabled === true,agentBusy:agent.busy === true,
          intervalMinutes:agent.schedule ? agent.schedule.tickSeconds/60 : 0,
          todayTokens:today ? String(today.totalTokens) : '—',
          todayCost:today ? Number(today.costCny).toFixed(2) : '—'
        });
        this.showState();
        const snippet = data.ai && data.ai.snippet;
        const screenPhase = data.phase === 'preview' ||
          (SCREEN_BASE && data.phase === 'play' && snippet && snippet.task === 'game');
        if (SCREEN_BASE) {
          const url = snippet && screenPhase
            ? SCREEN_BASE + '/screen.jpg?id=' + encodeURIComponent(snippet.id) + '&mode=' + data.phase : '';
          if (url !== this.previewUrl) {
            this.previewUrl = url;
            clearTimeout(this.previewTimer);
            if (url) this.refreshScreen(url, snippet.id, data.phase);
          }
        } else {
          clearTimeout(this.previewTimer);
          this.previewUrl = '';
          if (snippet && screenPhase) {
            this.refreshApiScreen(snippet.id, data.phase);
          } else {
            this.screenKey = '';
          }
        }
        if (!this.connected) console.info('卡噗工位已同步',data.phase);
        this.connected=true;
        if (!this.data.selected) this.setData({status:'已连接 · '+data.phaseLabel});
      },
      fail: err => {
        if(!this.active) return;
        if(this.connected!==false) console.warn('卡噗连接失败',err.errMsg);
        this.connected=false;
        this.setData({status:err.errMsg || '玩法服务未连接'});
      },
      complete: () => { if(this.active) this.timer=setTimeout(()=>this.sync(),1000); },
    };
    try {
      if (API_BASE) {
        tt.request({url:API_BASE + statePath,timeout:4000,...callbacks});
      } else {
        if (!this.cloud) this.cloud = tt.createCloud(CLOUD);
        this.cloud.callContainer({path:statePath,init:{method:'GET',timeout:4000},...callbacks});
      }
    } catch (err) {
      callbacks.fail({errMsg:err.message || '云容器调用失败'});
      callbacks.complete();
    }
  },
  operatorInput(event) {
    this.operatorToken = event.detail.value.trim();
    this.setData({operatorEntered:!!this.operatorToken,controlMessage:''});
  },
  changeControl(event) {
    const {action,minutes} = event.currentTarget.dataset;
    if (!this.data.controlsReady || !this.operatorToken || this.data.saving) return;
    let path,body;
    if (action === 'ai') {path='/api/ai/pause';body={paused:this.data.aiOn};}
    else if (action === 'auto') {path='/api/agent/toggle';body={start:!this.data.autoOn};}
    else if (action === 'interval') {path='/api/agent/interval';body={tickSeconds:Number(minutes)*60};}
    else if (action === 'step' && this.data.aiOn && !this.data.agentBusy) {
      path='/api/agent/step';body={force:true};
    } else return;
    this.setData({saving:true,controlMessage:action === 'step' ? '正在推进工作…' : '正在保存…'});
    const header = {'Content-Type':'application/json','X-Kapu-Token':this.operatorToken};
    const callbacks = {
      success:({statusCode,data})=>{
        if (!this.active) return;
        if (typeof data === 'string') {try {data=JSON.parse(data);} catch {data=null;}}
        if (statusCode === 401) return this.setData({controlMessage:'请核对主播口令，服务端需配置 API_TOKEN'});
        if (statusCode !== 200 || !data || !data.ok) {
          const reason = data && (data.skipped || data.error);
          const messages = {busy:'正在工作中','no-key':'服务端未配置模型密钥','ai-paused':'AI 已暂停'};
          return this.setData({controlMessage:messages[reason] || '操作未完成，请检查服务状态'});
        }
        const patch = {controlMessage:action === 'step' ? '已完成一步工作' : '设置已保存'};
        if (typeof data.paused === 'boolean') patch.aiOn = !data.paused;
        if (typeof data.autoEnabled === 'boolean') patch.autoOn = data.autoEnabled;
        if (data.tickSeconds) patch.intervalMinutes = data.tickSeconds/60;
        this.setData(patch);
      },
      fail:()=>{if(this.active)this.setData({controlMessage:'请求未确认，请查看最新工作状态'});},
      complete:()=>{if(this.active)this.setData({saving:false});}
    };
    try {
      if (API_BASE) tt.request({url:API_BASE+path,method:'POST',header,data:body,timeout:60000,...callbacks});
      else this.cloud.callContainer({path,init:{method:'POST',header,body,timeout:60000},...callbacks});
    } catch {callbacks.fail();callbacks.complete();}
  },
  triggerControl(action) {
    const header = {'Content-Type':'application/json','X-Kapu-Token':this.operatorToken || ''};
    const body = {action};
    const callbacks = {
      success: ({statusCode,data}) => {
        if (!this.active) return;
        if (typeof data === 'string') { try { data = JSON.parse(data); } catch { data = null; } }
        if (statusCode === 401) return this.setData({status:'需要主播口令（API_TOKEN）'});
        if (statusCode !== 200 || !data || !data.ok) {
          const reason = data && (data.reason || data.error);
          this.setData({status:reason === 'no-work' ? '还没有可展示的作品，先点个单'
            : reason && reason.indexOf('phase:') === 0 ? '当前阶段 ' + reason.slice(6) + '，等回到 work 再试'
            : reason === 'unknown-control' ? '服务端还是旧版本，重新部署最新代码'
            : '服务端没有进入 ' + action});
        }
      },
      fail: err => { if (this.active) this.setData({status:(err && err.errMsg) || '服务端请求失败'}); },
    };
    try {
      if (API_BASE) {
        tt.request({url:API_BASE+'/api/control',method:'POST',header,data:body,timeout:8000,...callbacks});
      } else {
        this.ensureCloud().callContainer({path:'/api/control',init:{method:'POST',header,body,timeout:8000},...callbacks});
      }
    } catch (err) {
      this.setData({status:err.message || '服务端请求失败'});
    }
  },
  ensureCloud() {
    if (!this.cloud) this.cloud = tt.createCloud(CLOUD);
    return this.cloud;
  },
  refreshApiScreen(id, phase) {
    const key = phase + ':' + id;
    if (this.screenKey === key) return;
    this.screenKey = key;
    const path = '/api/screen?id=' + encodeURIComponent(id) + '&mode=' + phase;
    const callbacks = {
      success: ({data,statusCode}) => {
        if (!this.active) return;
        if (typeof data === 'string') {
          try { data = JSON.parse(data); } catch { data = null; }
        }
        if (statusCode && statusCode !== 200) return;
        if (!data || !data.ok || !data.image) return;
        this.card.receive({screenFrame:{id,phase,image:data.image}});
      },
      fail: err => console.warn('屏幕截图失败', err && err.errMsg || ''),
    };
    try {
      if (API_BASE) {
        tt.request({url:API_BASE + path, timeout:20000, ...callbacks});
      } else {
        this.ensureCloud().callContainer({path, init:{method:'GET',timeout:20000}, ...callbacks});
      }
    } catch (err) {
      console.warn('屏幕截图异常', err.message || err);
    }
  },
  refreshScreen(url, id, phase) {
    const active = () => this.active && this.previewUrl === url;
    if (!active()) return;
    let delay = 800;
    tt.request({
      url, responseType:'arraybuffer', timeout:5000,
      success: ({statusCode,data}) => {
        if (!active() || statusCode !== 200) return;
        const image = 'data:image/jpeg;base64,' + encodeFrame(data);
        this.card.receive({screenFrame:{id,phase,image}});
        delay = 180;
      },
      complete: () => {
        if (active()) this.previewTimer = setTimeout(() => this.refreshScreen(url,id,phase),delay);
      },
    });
  },
  onUnload() { this.active=false;clearTimeout(this.timer);clearTimeout(this.previewTimer);clearInterval(this.countdownTimer);clearTimeout(this.bossTimer);if(this.card) this.card.stop(); },
  showState() {
    const state = this.liveState;
    if (!state || !this.card) return;
    const phase = this.data.selected;
    const action = this.data.actions.find(item => item.phase === phase);
    const remaining = Math.max(0,Math.ceil((this.demoEnds-Date.now())/1000));
    if (action && remaining > 0) {
      const scene = phase === 'pickup' && remaining < 6 ? 'play'
        : phase === 'inspect' && this.demoCaught ? 'caught' : phase;
      this.card.receive({...state,phase:scene,phaseLabel:scene === 'caught' ? '摸鱼被抓' : action.label,timeLeft:remaining,
        votes:{1:0,2:0},extended:false,paused:false,viewport:this.viewport});
      /* 摸鱼/拿掌机时，根据 bossThreshold 阈值随机触发老板查岗 */
      if ((phase === 'play' || phase === 'pickup') && !this.bossTimer) {
        const { min, max } = this.data.bossThreshold;
        const delay = (min + Math.random() * (max - min)) * 1000;
        if (Date.now()+delay >= this.demoEnds) return;
        this.bossArrivesAt = Date.now() + delay;
        console.info('老板预计', Math.round(delay/1000), '秒后到达战场');
        this.startBossCountdown();
        this.bossTimer = setTimeout(() => {
          this.stopBossCountdown();
          this.bossTimer = null;
          this.bossArrivesAt = 0;
          if (this.data.selected !== phase) return;
          const bossAction = this.data.actions.find(item => item.phase === 'inspect');
          this.demoCaught = true;
          this.demoEnds = Date.now() + 6000;
          this.setData({selected: 'inspect', status: '动作预演 · ' + bossAction.label});
          this.showState();
        }, delay);
      }
    } else {
      this.stopBossCountdown();
      clearTimeout(this.bossTimer);
      this.bossTimer = null;
      if (phase) this.setData({selected:'',status:'已连接 · '+state.phaseLabel});
      this.card.receive({...state,viewport:this.viewport});
    }
  },
  startBossCountdown() {
    this.stopBossCountdown();
    this.countdownTimer = setInterval(() => {
      if (!this.bossArrivesAt) { this.stopBossCountdown(); return; }
      const seconds = Math.max(0, Math.ceil((this.bossArrivesAt - Date.now()) / 1000));
      this.setData({ bossCountdown: seconds });
    }, 500);
  },
  stopBossCountdown() {
    clearInterval(this.countdownTimer);
    this.countdownTimer = null;
    this.setData({ bossCountdown: 0 });
  },
  mockPhase(e) {
    if (!this.liveState) {
      this.setData({status:'玩法状态未连接，先检查云调用权限'});
      return;
    }
    const phase = e.currentTarget.dataset.phase;
    const action = this.data.actions.find(item => item.phase === phase);
    if (phase === 'preview' || phase === 'avatar') {
      clearTimeout(this.bossTimer);
      this.bossTimer = null;
      this.stopBossCountdown();
      this.demoEnds = 0;
      this.setData({selected:''});
      this.triggerControl(phase);
      this.setData({status:'请求服务端 · ' + (action ? action.label : phase)});
      return;
    }
    const previous = this.data.selected && Date.now() < this.demoEnds ? this.data.selected : this.liveState.phase;
    this.demoCaught = phase === 'inspect' && ['pickup','play','caught'].includes(previous);
    clearTimeout(this.bossTimer);
    this.bossTimer = null;
    this.stopBossCountdown();
    this.demoEnds = Date.now() + 6000;
    this.setData({selected:phase,status:action ? '动作预演 · '+action.label : '已连接 · '+this.liveState.phaseLabel});
    this.showState();
  },
});
