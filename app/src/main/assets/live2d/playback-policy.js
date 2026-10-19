/* Native energy plans own the FPS limits; do not apply a second hidden sleep cap. */
window.Live2DPlayback = {
  rate(plan, idleMs) {
    if (!plan.running || plan.staticOnly || !(plan.fps > 0)) return 0;
    const active = Math.min(30, plan.fps);
    return idleMs >= (plan.idleAfterMs ?? 6000)
      ? Math.max(0, Math.min(active, plan.idleFps ?? active)) : active;
  }
};
