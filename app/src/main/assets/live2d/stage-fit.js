/* Fit visible artwork rather than the often oversized transparent model canvas. */
window.Live2DFit = (() => {
  const boxes = new WeakMap();
  function fit(app, model) {
    if (!app || !model || !innerWidth || !innerHeight) return;
    app.renderer.resize(innerWidth, innerHeight);
    let box = boxes.get(model);
    if (!box) {
      const w = model.internalModel.width, h = model.internalModel.height;
      const scale = Math.min(innerWidth * .9 / w, innerHeight * .9 / h);
      model.anchor.set(0); model.pivot.set(0); model.scale.set(scale);
      model.position.set((innerWidth - w * scale) / 2, (innerHeight - h * scale) / 2);
      box = {x:0, y:0, width:w, height:h};
      try {
        app.renderer.render(app.stage);
        const pixels = app.renderer.extract.pixels(undefined, new PIXI.Rectangle(0,0,innerWidth,innerHeight));
        const resolution = app.renderer.resolution;
        const pw = Math.round(innerWidth * resolution), ph = Math.round(innerHeight * resolution);
        let left=pw, right=-1, top=ph, bottom=-1;
        for (let y=0; y<ph; y++) for (let x=0; x<pw; x++) {
          if (pixels[(y*pw+x)*4+3] > 20) {
            left=Math.min(left,x); right=Math.max(right,x); top=Math.min(top,y); bottom=Math.max(bottom,y);
          }
        }
        if (right > left && bottom > top) box = {
          x:(left/resolution-model.x)/scale, y:((ph-1-bottom)/resolution-model.y)/scale,
          width:(right-left+1)/resolution/scale, height:(bottom-top+1)/resolution/scale
        };
      } catch (_) { /* Canvas bounds remain a safe fallback. */ }
      boxes.set(model, box);
    }
    if (window.CompanionLayout?.fit(app, model, box)) return;
    const landscape = innerWidth > innerHeight;
    const immersive = new URLSearchParams(location.search).get('immersive') === 'true';
    const portraitClock = immersive && !landscape;
    const heightFill = landscape ? (immersive ? 1.25 : .98) : (portraitClock ? .70 : .90);
    const scale = Math.min(innerWidth * .90 / box.width, innerHeight * heightFill / box.height);
    model.anchor.set(0); model.pivot.set(box.x+box.width/2, box.y+box.height/2);
    model.scale.set(scale);
    model.position.set(innerWidth/2, landscape && immersive ? innerHeight*.03 + box.height*scale/2
      : portraitClock ? innerHeight*.60 : innerHeight/2);
    app.renderer.render(app.stage);
  }
  return {fit};
})();
