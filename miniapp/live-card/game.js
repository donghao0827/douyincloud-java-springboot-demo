const {layoutSource,codeFont} = require('./editor');
const phases = {work:0, hint:0, play:1, hide:2, caught:3, pickup:4, coffee:5, preview:6, avatar:7};
const sequence = [
  [0,1,2,2,3,4,5,5,1,2,4,5], [0,0,1,1,2,2,0,3,3,3,2,2],
  [0,0,1,1,1,2,2,2,3,3,3,3], [0,0,1,1,1,2,2,2,3,3,3,3],
  [0,0,0,0,1,1,1,2,2,2,2,2], [0,0,1,1,2,2,2,2,1,1,0,0],
  [0,0,0,0,1,1,1,1,1,1,1,1], [0,0,0,0,0,0,1,1,1,1,1,1],
];


function drawAvatarPreview(c, state, snippet, rect, text) {
  const draw = snippet && snippet.draw;
  const shapes = draw && draw.shapes;
  if (!shapes || !shapes.length) return;
  const ox = 45, oy = 321, scale = 72 / 200;
  const X = v => ox + v * scale, Y = v => oy + v * scale, R = v => v * scale;
  const bg = /^#[0-9a-f]{6}$/i.test(draw.bg || '') ? draw.bg : '#20332f';
  rect(18, 321, 124, 72, bg);
  const started = snippet.at || Date.now();
  const shown = Math.min(shapes.length, 1 + Math.floor((Date.now() - started) / 220));
  c.save();
  c.beginPath(); c.rect(18, 321, 124, 72); c.clip();
  c.lineCap = 'round'; c.lineJoin = 'round';
  const paint = s => {
    if (s.fill) { c.fillStyle = s.fill; c.fill(); }
    if (s.stroke) { c.strokeStyle = s.stroke; c.lineWidth = Math.max(1, (s.width || 2) * scale); c.stroke(); }
  };
  const rounded = (x, y, w, h, r) => {
    r = Math.min(r || 0, w / 2, h / 2);
    c.beginPath(); c.moveTo(x + r, y);
    c.lineTo(x + w - r, y); c.quadraticCurveTo(x + w, y, x + w, y + r);
    c.lineTo(x + w, y + h - r); c.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    c.lineTo(x + r, y + h); c.quadraticCurveTo(x, y + h, x, y + h - r);
    c.lineTo(x, y + r); c.quadraticCurveTo(x, y, x + r, y);
  };
  for (let i = 0; i < shown; i++) {
    const s = shapes[i];
    if (s.type === 'circle') { c.beginPath(); c.arc(X(s.cx), Y(s.cy), Math.max(1, R(s.r)), 0, 7); paint(s); }
    else if (s.type === 'rect') { rounded(X(s.x), Y(s.y), s.w * scale, s.h * scale, (s.rx || 0) * scale); paint(s); }
    else if (s.type === 'polygon' && s.points && s.points.length) {
      c.beginPath(); s.points.forEach((p, j) => j ? c.lineTo(X(p[0]), Y(p[1])) : c.moveTo(X(p[0]), Y(p[1])));
      c.closePath(); paint(s);
    } else if (s.type === 'line') {
      c.beginPath(); c.moveTo(X(s.x1), Y(s.y1)); c.lineTo(X(s.x2), Y(s.y2)); paint(s);
    } else if (s.type === 'arc') {
      c.beginPath(); c.arc(X(s.cx), Y(s.cy), Math.max(1, R(s.r)), s.start, s.end); paint(s);
    }
  }
  c.restore();
}

function drawGamePreview(c, state, snippet, rect, text) {
  const id = (state.currentGame && state.currentGame.id) || '';
  const title = (state.currentGame && state.currentGame.title) || (snippet && snippet.title) || '';
  const kind = /catch|橘子/.test(id + title) ? 'catch'
    : /whack|老板/.test(id + title) ? 'whack'
    : /memory|翻牌/.test(id + title) ? 'memory'
    : 'catch';
  const t = Date.now() / 1000;
  rect(18, 321, 124, 72, '#f7f2df');
  if (kind === 'catch') {
    text('接橘子', 22, 331, 7, '#2f4a34');
    const x = 80 + Math.sin(t * 2.2) * 38;
    const y = 336 + ((t * 38) % 48);
    c.fillStyle = '#e88535';
    c.beginPath(); c.arc(x, y, 4.5, 0, 7); c.fill();
    c.fillStyle = '#466747';
    c.fillRect(68, 386, 24, 4);
  } else if (kind === 'whack') {
    text('拍老板', 22, 331, 7, '#2f4a34');
    for (let i = 0; i < 9; i++) {
      const x = 40 + (i % 3) * 34;
      const y = 336 + Math.floor(i / 3) * 18;
      const hot = Math.floor(t * 1.4) % 9 === i;
      rect(x, y, 30, 14, hot ? '#c96a34' : '#d9c7a1');
      if (hot) text('老板', x + 4, y + 10, 6, '#fff6e4');
    }
  } else {
    text('翻牌记忆', 22, 331, 7, '#2f4a34');
    const chars = ['咖', '橘', '码'];
    for (let i = 0; i < 6; i++) {
      const x = 40 + (i % 3) * 34;
      const y = 340 + Math.floor(i / 3) * 24;
      const open = (Math.floor(t * 1.2) + i) % 6 < 2;
      rect(x, y, 30, 20, open ? '#fff8e8' : '#4b6e54');
      text(open ? chars[i % 3] : '?', x + 10, y + 14, 8, open ? '#2f4a34' : '#fff6e4');
    }
  }
}

Card({
  created() {
    this.canvas = this.getCanvas();
    this.ctx = this.canvas.getContext('2d');
    this.images = {};
    this.state = {phase:'work',phaseLabel:'认真上班'};
    this.started = Date.now();
    this.lines = [];
    try {
      const sysInfo = tt.getSystemInfoSync();
      this.dpr = sysInfo.pixelRatio || 2;
    } catch (e) {
      this.dpr = 2;
    }
    this.baseW = 313;
    this.baseH = 556;
    for (const key of ['room','boss','cup','orange', ...Object.keys(sequence)]) {
      const image = tt.createImage();
      image.onload = () => { this.images[key] = image; };
      image.onerror = () => console.error('卡片素材加载失败', key);
      image.src = '/assets/office/' + key + (key === 'room' ? '.jpg' : '.png');
    }
    this.timer = setInterval(() => this.draw(), 1000 / 12);
  },
  fitCanvas(viewport) {
    if (!viewport || (this.width === viewport.width && this.height === viewport.height)) return;
    this.width = viewport.width;
    this.height = viewport.height;
    const dpr = this.dpr;
    this.canvas.width = Math.round(this.width * dpr);
    this.canvas.height = Math.round(this.height * dpr);
  },
  receive(state) {
    if (state.screenFrame) {
      const frame = state.screenFrame;
      const image = tt.createImage();
      this.pendingFrame = image;
      image.onload = () => {
        if (this.pendingFrame === image && this.snippetId === frame.id && this.state.phase === frame.phase)
          {
            if (!this.previewImage) console.info('工位已显示作品',frame.phase);
            this.previewImage = image;
          }
      };
      image.onerror = () => console.warn('屏幕帧加载失败', frame.phase);
      image.src = frame.image;
      return;
    }
    this.fitCanvas(state.viewport);
    if (!state.phase) return;
    if (state.phase !== this.state.phase) {
      this.started = Date.now();
      this.previewImage = this.pendingFrame = null;
    }
    this.state = state;
    const snippet = state.ai && state.ai.snippet;
    if (snippet && snippet.id !== this.snippetId) {
      this.snippetId = snippet.id;
      this.previewImage = this.pendingFrame = null;
      this.codeStarted = Date.now();
      this.lines = layoutSource(this.ctx,snippet,125);
    }
  },
  stop() { clearInterval(this.timer); },
  draw() {
    if (!this.width) return;
    const c = this.ctx, s = this.state, elapsed = Date.now() - this.started;
    const img = (key,x,y,w,h) => { if (this.images[key]) c.drawImage(this.images[key],x,y,w,h); };
    const rect = (x,y,w,h,color) => {c.fillStyle=color; c.fillRect(x,y,w,h);};
    const text = (str,x,y,size,color,max=288) => {
      c.fillStyle=color; c.font=size+'px sans-serif'; c.fillText(String(str),x,y,max);
    };
    c.save();
    const w = this.canvas.width, h = this.canvas.height;
    const scale = Math.max(w / this.baseW, h / this.baseH);
    const worldLeft = (w-this.baseW*scale)/2;
    // Leave room below the fixed payroll header for future interaction cards.
    const worldTop = (h - this.baseH * scale) * 0.68 + h * 0.055;
    rect(0,0,w,h,'#eedcc4');
    img('room',worldLeft,worldTop,this.baseW*scale,this.baseH*scale);
    // The header follows the card. The chair, actor and desk share one scene transform.
    c.save();
    const headerScale = Math.min(w/this.baseW,h/this.baseH);
    c.scale(headerScale,headerScale);
    const right = w/headerScale - 16;
    text('程序员 卡噗',32,22,13,'#344d3c');
    rect(15,51,right-14,53,'#344d3c');
    const p=s.payroll, today=s.usage && s.usage.today;
    if (p && today) {
      const money = (label,x,y,size,alignRight=false) => {
        c.font=size+'px sans-serif';
        const width = c.measureText(label).width;
        const left = alignRight ? x-width-19 : x;
        text(label,left,y,size,'#fff6dd');
        img('orange',left+width+2,y-size/2-9,18,18);
      };
      money('本月实得 '+p.actual.toFixed(2),23,69,13);
      money('约定月薪 '+p.base.toFixed(2),right-8,69,12,true);
      rect(23,76,right-31,3,'#68806b');
      rect(23,76,(right-31)*Math.max(0,Math.min(1,p.actual/p.base)),3,'#ffc26d');
      text('今日消耗 '+today.totalTokens+' token',23,96,11,'#e2ebdc');
      money('今日自费 '+today.costCny.toFixed(2),right-8,96,11,true);
    } else text('等待工资服务连接',23,83,13,'#fff6dd');
    if (['pickup','play','caught'].includes(s.phase)) {
      const preparing = s.phase === 'pickup';
      const inspecting = s.phase === 'caught';
      const title = inspecting ? '老板来查岗了' : preparing ? '卡噗拿出了掌机' : s.extended ? '再玩五秒胜出' : '要提醒卡噗收手吗？';
      const timerLabel = s.paused ? '已暂停' : '剩余 ' + s.timeLeft + ' 秒';
      const cardWidth = w / headerScale / 2;
      const left = right - cardWidth;
      const x = left + 8, optionWidth = cardWidth - 16;
      const roundBox = (x,y,width,height,radius,fill) => {
        c.beginPath();
        c.moveTo(x+radius,y);
        c.arcTo(x+width,y,x+width,y+height,radius);
        c.arcTo(x+width,y+height,x,y+height,radius);
        c.arcTo(x,y+height,x,y,radius);
        c.arcTo(x,y,x+width,y,radius);
        c.closePath();
        c.fillStyle = fill;
        c.fill();
      };
      roundBox(left,112,cardWidth,68,10,'#85694d');
      roundBox(left,110,cardWidth,68,10,'#ad825a');
      roundBox(left+1.5,111.5,cardWidth-3,65,9,'#fff3da');
      c.font = '10px sans-serif';
      const timerWidth = c.measureText(timerLabel).width;
      text(title,x,125,11,'#344d3c',optionWidth-timerWidth-6);
      roundBox(x+optionWidth-timerWidth-6,114,timerWidth+8,14,7,'#f4dfb9');
      text(timerLabel,x+optionWidth-timerWidth-2,125,10,'#735330');
      const labels = inspecting ? ['扣薪','求情'] : ['提醒收手','再玩五秒'];
      const totalVotes = s.votes[1] + s.votes[2];
      labels.forEach((label,index) => {
        const y = 130 + 22*index;
        roundBox(x,y,optionWidth,20,8,index === 0 ? '#dceadc' : '#f7dfb9');
        if (totalVotes && s.votes[index+1]) {
          const barWidth = (optionWidth-14)*s.votes[index+1]/totalVotes;
          rect(x+7,y+18,barWidth,1.5,index === 0 ? '#729773' : '#c88a4c');
        }
        roundBox(x+3,y+2,28,16,7,index === 0 ? '#416749' : '#975528');
        text('发 '+(index+1),x+7,y+13.5,10,'#fff9e9');
        text(label,x+35,y+14,11,'#314737',optionWidth-74);
        const count = s.votes[index+1] + '票';
        c.font = '10px sans-serif';
        const countWidth = Math.min(34,c.measureText(count).width);
        text(count,x+optionWidth-countWidth-4,y+14,10,'#485647',34);
      });
    }
    c.restore();
    c.translate(worldLeft,worldTop);
    c.scale(scale,scale);
    if (s.phase==='caught' || s.phase==='inspect') img('boss',-8,206,168,150);
    const row=phases[s.phase] || 0;
    let step=Math.floor(elapsed/(row===5?166:83));
    step=row<2||row===7 ? step%12 : Math.min(step,11);
    if (this.images[row]) c.drawImage(this.images[row],sequence[row][step]*276,0,276,228,144,251,169,140);
    const metal = c.createLinearGradient(70,0,87,0);
    metal.addColorStop(0,'#39403e'); metal.addColorStop(0.55,'#868d88'); metal.addColorStop(1,'#444b48');
    rect(70,396,17,20,metal);
    c.beginPath();
    c.moveTo(44,416); c.bezierCurveTo(46,412,109,412,113,416);
    c.lineTo(113,418); c.bezierCurveTo(95,420,58,420,44,418);
    c.closePath(); c.fillStyle='#434a46'; c.fill();
    const snippet = s.ai && s.ai.snippet;
    const gameScreen = s.phase === 'pickup' || s.phase === 'play';
    const avatarDraw = s.phase === 'avatar' && snippet && snippet.draw && snippet.draw.shapes;
    const showPreview = s.phase === 'preview' || s.phase === 'avatar' ||
      (s.phase === 'play' && snippet && snippet.task === 'game');
    // Scale vertical strips around the lower edge. The far side is
    // shorter, so the top bezel slopes more than the base.
    for (let left = 10; left < 152; left += 4) {
      const width = Math.min(4,152-left);
      const depth = (left+width/2-10)/142;
      const verticalScale = 1.22 - 0.26*depth;
      const slope = 0.115;
      const lowerEdge = 398 + 0.03*(left+width/2-10);
      c.save();
      c.transform(1,slope,0,verticalScale,0,lowerEdge-399*verticalScale-(left+width/2)*slope);
      c.beginPath();
      c.rect(left,305,width+0.12,94);
      c.clip();
      rect(10,305,142,94,'#363e3b'); rect(11,306,140,1,'#87938a');
      rect(14,309,134,84,'#1d302a'); rect(137,395,5,1,'#a5d4ab');
      rect(14,309,134,12,'#344a42');
      text(gameScreen && ((s.currentGame && s.currentGame.title) || '小游戏') || snippet && snippet.title || '工位',18,318,6.5,'#dfebdf',96);
      text(showPreview || gameScreen ? '预览' : '源码',126,318,6.5,'#c4d4bf');
      if (avatarDraw) drawAvatarPreview(c, s, snippet, rect, text);
      else if (showPreview && this.previewImage) c.drawImage(this.previewImage,20,321,122,72);
      else if (gameScreen) drawGamePreview(c, s, snippet, rect, text);
      else if (showPreview) text('正在打开作品…',24,361,8,'#dae9d8');
      else if (this.lines.length) {
        const ticks = Math.floor((Date.now()-this.codeStarted)/320);
        const visible = Math.min(this.lines.length, 2 + ticks % (this.lines.length+12));
        const start = Math.max(0,visible-8);
        c.font = codeFont;
        for (let i=start;i<visible;i++) {
          for (const run of this.lines[i]) {
            c.fillStyle = run.color;
            c.fillText(run.text,18+run.x,328+(i-start)*9);
          }
        }
      }
      c.restore();
    }
    if (s.phase!=='coffee') {
      rect(172,429,17,2,'#af8058'); img('cup',166,395,29,37);
    }
    rect(245,430,6,2,'#71634f'); rect(287,430,6,2,'#71634f');
    rect(249,408,10,2,'#a76d51'); rect(239,410,59,20,'#cab792');
    rect(240,410,57,18,'#f0dfbd'); rect(243,413,51,13,'#30473c');
    const d=new Date(), parts=[d.getHours(),d.getMinutes(),d.getSeconds()].map(n=>('0'+n).slice(-2));
    text(parts[0]+':'+parts[1],246,424,11,'#e4efc9');
    text(parts[2],279,424,8,'#c4dbac');
    c.restore();
  }
});
