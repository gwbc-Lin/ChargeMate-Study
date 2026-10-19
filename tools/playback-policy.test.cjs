const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'); const vm=require('node:vm');
const scope={window:{}}; vm.runInNewContext(fs.readFileSync('app/src/main/assets/live2d/playback-policy.js','utf8'),scope);
const rate=scope.window.Live2DPlayback.rate;
const plan={running:true,fps:30,idleFps:24,idleAfterMs:6000,sleepAfterMs:25000};
test('long idle preserves native plan instead of hidden 2 FPS cap',()=>{assert.equal(rate(plan,5000),30);assert.equal(rate(plan,6000),24);assert.equal(rate(plan,120000),24)});
test('static paused and explicit zero FPS remain stopped',()=>{assert.equal(rate({...plan,staticOnly:true},0),0);assert.equal(rate({...plan,running:false},0),0);assert.equal(rate({...plan,idleFps:0},6000),0);assert.equal(rate({...plan,fps:0},0),0)});
