window.CompanionLayout = {
  set(value) {
    if (value === undefined) return false;
    const next = value === 'bust-split' ? value : 'centered';
    const changed = document.body.dataset.layout !== next;
    document.body.dataset.layout = next;
    return changed;
  },
  split() {
    return innerWidth > innerHeight && document.body.classList.contains('immersive') && document.body.dataset.layout === 'bust-split';
  },
  fit(app, model, box) {
    if (!this.split()) return false;
    const width = innerWidth * .65;
    app.renderer.resize(innerWidth, innerHeight);
    // Keep side artwork intact; the character can overlap the left edge of the clock.
    const scale = Math.min(innerHeight * .92 / (box.height * .42), width * .97 / box.width);
    if (model.anchor) model.anchor.set(0);
    model.pivot.set(box.x + box.width / 2, box.y);
    model.scale.set(scale); model.position.set(width / 2, innerHeight * .04);
    app.renderer.render(app.stage);
    return true;
  }
};
