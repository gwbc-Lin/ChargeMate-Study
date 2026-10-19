/* Request a vsync only when an animation frame is due, not on every 60/120 Hz refresh. */
window.CompanionFrames = {
  create({fps, frame, failed = error => { throw error; }}) {
    let running = false, timer = null, raf = null, previous = 0, deadline = 0;
    function stop() {
      running = false;
      if (timer !== null) clearTimeout(timer);
      if (raf !== null) cancelAnimationFrame(raf);
      timer = raf = null;
    }
    function schedule() {
      timer = setTimeout(() => {
        timer = null;
        if (running) raf = requestAnimationFrame(tick);
      }, Math.max(0, deadline - performance.now() - 1));
    }
    function tick(now) {
      raf = null;
      if (!running) return;
      const rate = Math.min(30, Math.max(0, fps()));
      if (!rate) { stop(); return; }
      const elapsed = Math.min(250, Math.max(0, now - previous));
      previous = now;
      try { frame(elapsed, now); } catch (error) { stop(); failed(error); return; }
      if (!running) return;
      deadline += 1000 / rate;
      if (deadline <= now) deadline = now + 1000 / rate;
      schedule();
    }
    return {start() {
      if (running) return;
      running = true; previous = deadline = performance.now(); schedule();
    }, stop};
  },
  prepare(app) {
    app.stop();
    // Native DOM pointer handlers own interactions; Pixi hit testing needs no system ticker.
    const interaction = app.renderer.plugins?.interaction;
    if (interaction) interaction.useSystemTicker = false;
    app.renderer.events?.removeEvents();
  }
};
