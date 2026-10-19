(() => {
  const initial = new URLSearchParams(location.search);
  const enabled = initial.get('immersive') === 'true';
  document.body.classList.toggle('immersive', enabled);
  let timer, running = false;
  function paint() {
    const now = new Date(), hh = String(now.getHours()).padStart(2,'0'), mm = String(now.getMinutes()).padStart(2,'0');
    document.getElementById('hours').textContent = hh;
    document.getElementById('minutes').textContent = mm;
    document.getElementById('clock').setAttribute('aria-label',`当前时间 ${hh}:${mm}`);
  }
  function stop() { clearTimeout(timer); timer = undefined; }
  function tick() { paint(); timer = setTimeout(tick, 60000 - Date.now()%60000 + 50); }
  window.CompanionClock = {
    setTopInset(value) {
      if (value === undefined || value === null) return;
      document.documentElement.style.setProperty('--clock-top-inset', `${Math.max(0, Number(value) || 0)}px`);
    },
    setStyle(style) {
      if (style === undefined || style === null) return;
      const next = ['aurora','minimal','neon','mint','amber'].includes(style) ? style : 'aurora';
      const clock = document.getElementById('clock');
      if (clock.dataset.style !== next) clock.dataset.style = next;
    },
    setRunning(next) {
      const active = enabled && next && !document.hidden;
      if (active === running) return;
      running = active; stop(); if (active) tick();
    }, stop
  };
  // Seed geometry before the first paint, rather than waiting for the model's ready event.
  window.CompanionClock.setTopInset(initial.get('clockTopInset'));
  window.CompanionClock.setStyle(initial.get('clockStyle'));
  const layout = initial.get('landscapeLayout');
  if (layout !== null) document.body.dataset.layout = layout === 'bust-split' ? layout : 'centered';
  if (enabled) paint();
})();
