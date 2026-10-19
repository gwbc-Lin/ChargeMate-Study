window.startSpine = async (config, send) => {
  const script = src => new Promise((resolve, reject) => {
    const tag = document.createElement('script'); tag.src = src;
    tag.onload = resolve; tag.onerror = () => reject(Error('Spine 运行库加载失败')); document.head.append(tag);
  });
  await script('vendor/pixi7.min.js'); await script('vendor/pixi-spine.js');
  // ImageBitmap decoding may premultiply pixels before the atlas applies its pma flag.
  // Use HTMLImageElement so WebGL upload respects each atlas page's alpha mode.
  PIXI.Assets.setPreferences({preferCreateImageBitmap: false, preferWorkers: false});
  const response = await fetch(config.url);
  if (!response.ok) throw Error('Spine 配置读取失败');
  const definition = await response.json();
  const skeletonUrl = new URL(definition.skeleton, new URL(config.url, location.href)).href;
  const atlasUrl = new URL(definition.atlas, new URL(config.url, location.href)).href;
  const data = await PIXI.Assets.load({src: skeletonUrl, data: {spineAtlasFile: atlasUrl}});
  const app = new PIXI.Application({view: document.getElementById('stage'), width: innerWidth,
    height: innerHeight, backgroundAlpha: 0, antialias: true, autoStart: false,
    resolution: Math.min(devicePixelRatio || 1, 1.25), autoDensity: true});
  window.CompanionFrames.prepare(app);
  const model = new PIXI.spine.Spine(data.spineData); model.autoUpdate = false;
  app.stage.addChild(model);
  const animations = model.spineData.animations.map(a => a.name);
  const idle = animations.find(n => n.toLowerCase() === 'idle') || animations.find(n => /idle/i.test(n)) || animations[0];
  if (idle) model.state.setAnimation(0, idle, true);
  model.update(0);
  const box = model.getLocalBounds().clone();
  if (!(box.width > 0 && box.height > 0)) { app.destroy(false, {children:true}); throw Error('Spine 没有可显示的贴图'); }
  let loop;
  let last = {running:false, fps:15}, interaction = performance.now(), disposed = false;
  function layout() {
    if (disposed) return;
    if (window.CompanionLayout.fit(app, model, box)) return;
    app.renderer.resize(innerWidth, innerHeight);
    const wide = innerWidth > innerHeight;
    const immersive = new URLSearchParams(location.search).get('immersive') === 'true';
    const fill = wide ? (immersive ? 1.25 : .98) : (immersive ? .70 : .90);
    const scale = Math.min(innerWidth * .90 / box.width, innerHeight * fill / box.height);
    model.pivot.set(box.x + box.width / 2, box.y + box.height / 2); model.scale.set(scale);
    model.position.set(innerWidth / 2, wide && immersive ? innerHeight * .03 + box.height * scale / 2 : immersive ? innerHeight * .60 : innerHeight / 2);
    app.renderer.render(app.stage);
  }
  function state(next) {
    last = next;
    if (window.CompanionLayout.set(next.landscapeLayout)) layout();
    window.CompanionClock.setTopInset(next.clockTopInset);
    window.CompanionClock.setStyle(next.clockStyle); window.CompanionClock.setRunning(next.running);
    if (disposed) return;
    if (next.running && !next.staticOnly && next.fps > 0 && !document.hidden) {
      loop?.start();
    } else { loop?.stop(); app.renderer.render(app.stage); }
  }
  function animate() {
    if (!last.running || last.staticOnly || !last.interactionEnabled) return;
    interaction = performance.now();
    // 这批内置角色统一带有 action 动画：点击优先播它，其余模型退回常见动作名。
    const action = animations.find(n => n.toLowerCase() === 'action')
      || animations.find(n => /touch|tap|happy|greeting/i.test(n));
    if (action) { model.state.setAnimation(0, action, false); if (idle) model.state.addAnimation(0, idle, true, 0); }
    state(last);
  }
  const visibility = () => state(last);
  document.addEventListener('visibilitychange', visibility); window.addEventListener('resize', layout);
  app.view.addEventListener('click', () => { send('tapped'); animate(); });
  let sampleStart = 0, sampleFrames = 0, reports = 0;
  loop = window.CompanionFrames.create({
    fps: () => window.Live2DPlayback.rate(last, performance.now() - interaction),
    failed: error => send('failed', error.message),
    frame: (elapsed, now) => {
      if (!sampleStart) sampleStart = now;
      sampleFrames++;
      if (reports < 2 && now - sampleStart >= (reports ? 30000 : 10000)) {
        send('performance', JSON.stringify({character:config.id, fps:Number((sampleFrames*1000/(now-sampleStart)).toFixed(1)), target:window.Live2DPlayback.rate(last,now-interaction)}));
        reports++; sampleFrames=0; sampleStart=now;
      }
      model.update(elapsed / 1000); app.renderer.render(app.stage);
    }
  });
  window.pet = {state, celebrate:animate, dispose() {
    disposed = true; document.removeEventListener('visibilitychange', visibility); window.removeEventListener('resize', layout);
    window.CompanionClock.stop(); loop?.stop(); app.destroy(false, {children:true, texture:true, baseTexture:true});
    PIXI.Assets.unload(skeletonUrl).catch(() => {});
  }};
  layout(); send('ready', config.id);
};
