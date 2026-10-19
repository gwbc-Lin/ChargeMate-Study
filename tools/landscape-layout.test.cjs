const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
function setup(width,height,immersive=true){
 const scope={window:{},innerWidth:width,innerHeight:height,document:{body:{dataset:{},classList:{contains:()=>immersive}}}};
 vm.runInNewContext(fs.readFileSync('app/src/main/assets/live2d/landscape-layout.js','utf8'),scope);
 return scope.window.CompanionLayout;
}
test('split preserves full artwork width without clipping at the divider',()=>{
 for(const [w,h] of [[1280,720],[2340,1080],[1024,768],[640,360]]){
 const layout=setup(w,h);layout.set('bust-split');
 let viewport;const model={pivot:{set(x,y){model.px=x;model.py=y}},scale:{set(s){model.s=s}},position:{set(x,y){model.x=x;model.y=y}}};
 assert.equal(layout.fit({renderer:{resize(w,h){viewport=[w,h]},render(){}}},model,{x:-80,y:-200,width:700,height:1800}),true);
 assert.deepEqual(viewport,[w,h]);assert.equal(model.y,h*.04);
 assert.equal(model.py,-200);assert.ok(model.s*700<=w*.65*.97+.001);assert.ok(model.x-model.s*700/2>0);assert.ok(model.x+model.s*700/2<w*.65);
 }
});
