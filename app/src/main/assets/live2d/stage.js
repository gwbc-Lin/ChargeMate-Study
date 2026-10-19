(() => {
  'use strict';
  const requested = new URLSearchParams(location.search).get('character');
  let character = requested, legacy = false;
  let tapGroup = null, hasExpressions = false;
  const loadScript = src => new Promise((resolve,reject) => { const tag=document.createElement('script'); tag.src=src; tag.onload=resolve; tag.onerror=()=>reject(Error('运行库加载失败')); document.head.append(tag); });
  let app, model, loop, lastInteraction = performance.now(), active = false, last = { running: false, fps: 15, staticOnly: false };
  const send = (name, value = '') => {
    if (window.PetBridge && typeof window.PetBridge[name] === 'function') window.PetBridge[name](String(value));
  };
  let pointerStart = null, suppressClickUntil = 0;
  window.addEventListener('pointerdown', e => {
    if (e.isPrimary !== false) pointerStart = {x:e.clientX,y:e.clientY,time:performance.now()};
  }, true);
  window.addEventListener('pointercancel', () => { pointerStart = null; }, true);
  window.addEventListener('pointerup', e => {
    const start = pointerStart; pointerStart = null;
    if (!start || new URLSearchParams(location.search).get('immersive') !== 'true') return;
    const dx = e.clientX-start.x, dy = e.clientY-start.y;
    if (Math.abs(dx) >= Math.max(48, innerWidth*.08) && Math.abs(dx)>Math.abs(dy)*1.4 && performance.now()-start.time<1200) {
      suppressClickUntil = performance.now()+400;
      e.preventDefault(); e.stopImmediatePropagation(); send('swiped', dx<0 ? 'next' : 'previous');
    }
  }, true);
  window.addEventListener('click', e => {
    if (performance.now()<suppressClickUntil) { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
  function layout() { window.Live2DFit.fit(app, model); }
  let sampleStart = 0, sampleFrames = 0, sampleReports = 0;
  let configuredFps = -1;
  function setFps(fps) {
    fps = Math.max(1, fps);
    if (fps !== configuredFps) { configuredFps = fps; }
  }
  function state(next) {
    if (window.CompanionLayout.set(next.landscapeLayout)) layout();
    window.CompanionClock.setStyle(next.clockStyle);
    window.CompanionClock.setTopInset(next.clockTopInset);
    const image = document.querySelector('.png-character');
    if (image && next.imagePlacement) {
      const p = next.imagePlacement;
      image.style.translate = `${p.x * innerWidth}px ${p.y * innerHeight}px`;
      image.style.scale = String(p.scale);
    }
    window.CompanionClock.setRunning(next.running);
    if (next.running && !active) { lastInteraction = performance.now(); sampleStart = 0; sampleFrames = 0; sampleReports = 0; }
    last = next; active = !!next.running;
    if (!app || !model) return;
    setFps(window.Live2DPlayback.rate(next, performance.now() - lastInteraction));
    if (active && !next.staticOnly && next.fps > 0 && !document.hidden) loop?.start();
    else { loop?.stop(); app.renderer.render(app.stage); }
  }
  window.pet = {
    state,
    celebrate() {
      if (model && active && !last.staticOnly) {
        if (hasExpressions) model.expression();
        if (tapGroup !== null) model.motion(tapGroup, undefined, 3).catch(() => {});
      }
    },
    dispose() {
      window.CompanionClock.stop();
      if (app) { loop?.stop(); app.destroy(false, { children: true, texture: true, baseTexture: true }); }
      app = model = null;
    }
  };
  document.addEventListener('visibilitychange', () => state(last));
  window.addEventListener('resize', layout);
  window.addEventListener('error', e => send('failed', e.message));
  window.addEventListener('unhandledrejection', e => send('failed', e.reason?.message || '模型加载异常'));
  (async () => {
    const response = await fetch('character.json');
    if (!response.ok) throw Error('角色配置无法读取');
    const config = await response.json();
    window.CompanionLayout.set(config.landscapeLayout);
    window.CompanionClock.setStyle(config.clockStyle);
    character = config.id; legacy = config.kind === 'cubism2';
    if (config.kind === 'png') {
      document.getElementById('stage').remove();
      const image = document.createElement('img'); image.alt = '充电陪伴角色';
      image.className = 'png-character';
      image.onload = () => { state(last); send('ready', config.id); }; image.onerror = () => send('failed','图片加载失败');
      image.addEventListener('click', () => send('tapped'));
      document.body.appendChild(image); image.src = config.url; return;
    }
    if (config.kind === 'spine') {
      await loadScript('spine-stage.js');
      await window.startSpine(config, send); return;
    }
    await loadScript('vendor/pixi.min.js');
    await loadScript(legacy ? 'vendor/live2d.js'  : 'vendor/live2dcubismcore.min.js');
    await loadScript(legacy ? 'vendor/cubism2.min.js' : 'vendor/cubism4.min.js');
    PIXI.live2d.config.sound = false;
    app = new PIXI.Application({ view: document.getElementById('stage'), width: innerWidth,
      height: innerHeight, backgroundAlpha: 0, antialias: true, autoStart: false,
      powerPreference: 'low-power', resolution: Math.min(devicePixelRatio || 1, 1.25), autoDensity: true });
    window.CompanionFrames.prepare(app);
    const url = config.url;
    const definition = await (await fetch(url)).json();
    const groups = legacy ? definition.motions || {} : definition.FileReferences.Motions || {};
    tapGroup = Object.prototype.hasOwnProperty.call(groups,'TapBody') ? 'TapBody' : Object.prototype.hasOwnProperty.call(groups,'') ? '' : Object.keys(groups)[0] ?? null;
    hasExpressions = legacy ? !!definition.expressions?.length : !!definition.FileReferences.Expressions?.length;
    model = await PIXI.live2d.Live2DModel.from(url, {
      autoUpdate: false, autoInteract: false, idleMotionGroup: Object.keys(groups).find(name => name.toLowerCase() === 'idle') || (legacy ? 'idle' : 'Idle'), motionPreload: PIXI.live2d.MotionPreloadStrategy.IDLE
    });
    app.stage.addChild(model);
    loop = window.CompanionFrames.create({
      fps: () => window.Live2DPlayback.rate(last, performance.now() - lastInteraction),
      failed: error => send('failed', error.message),
      frame: (elapsed, now) => {
      const idle = now - lastInteraction;
      if (!sampleStart) sampleStart = now;
      sampleFrames++;
      if (sampleReports < 2 && now - sampleStart >= (sampleReports === 0 ? 10000 : 30000)) {
        send('performance', JSON.stringify({character, fps:Number((sampleFrames*1000/(now-sampleStart)).toFixed(1)), target:configuredFps}));
        sampleReports++; sampleStart=now; sampleFrames=0;
      }
      const fps = window.Live2DPlayback.rate(last, idle);
      setFps(fps);
      if (!fps) { loop?.stop(); return; }
      model.update(elapsed);
      app.renderer.render(app.stage);
    }});
    model.update(16); layout();
    document.getElementById('stage').addEventListener('click', e => {
      send('tapped'); lastInteraction = performance.now();
      if (!active || last.staticOnly || !last.interactionEnabled) return;
      setFps(last.fps || 30);
      loop?.start();
      model.focus((e.clientX / innerWidth - 0.5) * 2, (0.5 - e.clientY / innerHeight) * 2);
      if (tapGroup !== null) model.motion(tapGroup, undefined, 3).catch(() => {});
      if (hasExpressions) model.expression();
    });
    state(last); send('ready', config.id);
  })().catch(e => send('failed', e.message || 'Live2D 初始化失败'));
})();
