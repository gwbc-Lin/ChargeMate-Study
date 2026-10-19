const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'), vm=require('node:vm');
function layout(width,height,immersive){
 const model={internalModel:{width:200,height:1000},anchor:{set(){}},pivot:{set(x,y){model.px=x;model.py=y}},scale:{set(s){model.s=s}},position:{set(x,y){model.x=x;model.y=y}}};
 const app={renderer:{resize(){},render(){}}};
 const scope={window:{},innerWidth:width,innerHeight:height,URLSearchParams,location:{search:`?immersive=${immersive}`}};
 vm.runInNewContext(fs.readFileSync('app/src/main/assets/live2d/stage-fit.js','utf8'),scope);
 scope.window.Live2DFit.fit(app,model); return model;
}
test('landscape close framing preserves head and enlarges the character',()=>{
 const m=layout(900,400,true); assert.equal(m.s*1000,500);
 assert.equal(m.y-m.py*m.s,12); assert.ok(m.y+500*m.s>400);
});
test('portrait reserves a separate clock area above the complete character',()=>{
 for(const [w,h] of [[320,568],[393,852],[600,960],[800,1280]]){
  const m=layout(w,h,true);
  assert.ok(m.y-m.py*m.s>=h*.25-.001);assert.ok(m.y+m.py*m.s<=h*.95+.001);
  assert.ok(m.s*200<=w*.9+.001);
 }
});
