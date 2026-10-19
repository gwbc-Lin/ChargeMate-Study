const {test}=require('node:test'), assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
function simulation(refresh=120){
 let now=0,id=0;const jobs=new Map(),frames=[],errors=[];let rate=30;
 const add=(fn,at)=>{jobs.set(++id,{fn,at});return id};
 const scope={window:{},performance:{now:()=>now},setTimeout:(fn,delay)=>add(fn,now+delay),clearTimeout:id=>jobs.delete(id),
  requestAnimationFrame:fn=>add(()=>fn(now),Math.ceil((now+.001)/(1000/refresh))*(1000/refresh)),cancelAnimationFrame:id=>jobs.delete(id)};
 vm.runInNewContext(fs.readFileSync('app/src/main/assets/live2d/frame-loop.js','utf8'),scope);
 const loop=scope.window.CompanionFrames.create({fps:()=>rate,frame:(dt,t)=>frames.push({dt,t}),failed:e=>errors.push(e)});
 function advance(end){let limit=100000;while(jobs.size&&limit--){const [key,job]=[...jobs].sort((a,b)=>a[1].at-b[1].at)[0];if(job.at>end)break;jobs.delete(key);now=job.at;job.fn()};assert.ok(limit>0);now=end}
 return {loop,frames,jobs,advance,setRate:r=>rate=r};
}
test('30 fps rendering does not call the model at display 120 Hz',()=>{
 const s=simulation();s.loop.start();s.advance(1000);assert.ok(s.frames.length>=29&&s.frames.length<=31);
 assert.ok(s.frames.slice(1).every(f=>f.dt>=24));
});
test('pause cancels timers and vsync, resume excludes hidden time',()=>{
 const s=simulation();s.loop.start();s.advance(500);s.loop.stop();const count=s.frames.length;
 assert.equal(s.jobs.size,0);s.advance(10000);assert.equal(s.frames.length,count);
 s.loop.start();s.advance(10050);assert.ok(s.frames[count].dt<34);
});
test('zero FPS stops and thermal low rate remains capped',()=>{
 const s=simulation();s.setRate(8);s.loop.start();s.advance(1000);assert.ok(s.frames.length<=9);
 s.setRate(0);s.advance(1200);assert.equal(s.jobs.size,0);
});
