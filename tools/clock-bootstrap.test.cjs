const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), vm = require('node:vm');

test('character reload paints the clock with its existing geometry and style', () => {
  const properties = {}, nodes = {clock: {dataset: {}, setAttribute() {}}, minutes: {}};
  const snapshots = [];
  nodes.hours = {set textContent(value) {
    snapshots.push([properties['--clock-top-inset'], nodes.clock.dataset.style, body.dataset.layout]);
  }};
  const body = {dataset: {}, classList: {toggle() {}}};
  const window = {};
  vm.runInNewContext(fs.readFileSync('app/src/main/assets/live2d/companion-clock.js', 'utf8'), {
    window, URLSearchParams, Date, setTimeout, clearTimeout,
    location: {search: '?immersive=true&clockTopInset=116&clockStyle=neon&landscapeLayout=bust-split'},
    document: {body, hidden: false, getElementById: id => nodes[id],
      documentElement: {style: {setProperty: (key, value) => {properties[key] = value;}}}},
  });
  assert.deepEqual(snapshots, [['116px', 'neon', 'bust-split']]);
  // Loading/visibility state can precede the native ready event; it must not reset geometry.
  window.CompanionClock.setTopInset(undefined);
  window.CompanionClock.setStyle(undefined);
  assert.equal(properties['--clock-top-inset'], '116px');
  assert.equal(nodes.clock.dataset.style, 'neon');
  window.CompanionClock.setTopInset(92);
  assert.equal(properties['--clock-top-inset'], '92px');
});
