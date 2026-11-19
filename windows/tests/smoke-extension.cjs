const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const dir = path.join(__dirname, '../Chrome') + path.sep;
const element = (text, parent, attrs={}, children={}) => ({
  textContent:text, innerText:text, parentElement:parent, getClientRects:()=>[{}],
  getAttribute:key=>attrs[key], querySelector:()=>null,
  querySelectorAll:selector=>children[selector] || [],
});
const renderer = vm.createContext({URL});
vm.runInContext(fs.readFileSync(dir+'reader.js','utf8'),renderer);
const stamp = '2026-11-21T12:30:00+08:00';
const usagePage = (items, useMeters) => {
  const all = [], meters = [];
  for (const [label,value] of items) {
    const time = element('12:30',null,{datetime:stamp});
    const section = element(label+'\n'+value+'\nResets 12:30',null,{}, {'time[datetime],[title]':[time]});
    all.push(element(label,section));
    if (useMeters) meters.push(element(value,section,{'aria-label':label,'aria-valuetext':value}));
  }
  return {
    querySelectorAll(selector) {return selector.includes('[role=') ? meters : all;},
    querySelector(){return null;},
  };
};
const examples = [
  ['chatgpt','https://chatgpt.com/codex/settings/usage',[['5-hour usage limit','73% remaining'],['Weekly usage limit','88% remaining']],false,[73,88]],
  ['chatgpt','https://chatgpt.com/codex/cloud/settings/analytics',[['5 小時用量上限','剩餘 73%'],['每週用量上限','剩餘 88%']],false,[73,88]],
  ['claude','https://claude.ai/settings/usage',[['Current session','18% used'],['All models','Used 39%']],true,[82,61]],
];
for (const [provider,url,items,meters,expected] of examples){
  const result = renderer.QuotaReader.extract(usagePage(items,meters),url,Date.now());
  assert.equal(result.provider,provider);
  assert.deepEqual(Array.from(result.windows,w=>w.remainingPct),expected);
  assert.equal(result.windows[0].resetAt,Date.parse(stamp));
}
assert.throws(()=>renderer.QuotaReader.extract(usagePage([['Weekly usage limit','79%']],false),examples[0][1]));
assert.equal(renderer.QuotaReader.percent('100% used'),0);
const handlers = {};
const data = {local:{},session:{}};
const out = [];
const storage = name => ({
  async get(keys){
    if (typeof keys === 'string') keys=[keys];
    return Object.fromEntries(keys.filter(k=>k in data[name]).map(k=>[k,data[name][k]]));
  },
  async set(values){Object.assign(data[name],values);},
});
const chrome = {
  storage:{local:storage('local'),session:storage('session')},
  runtime:{id:'verified',onMessage:{addListener:fn=>handlers.message=fn},onInstalled:{addListener(){}},
    onStartup:{addListener(){}},sendNativeMessage:async (host,payload)=>{out.push({host,payload});return {ok:true};}},
  alarms:{get:async()=>true,onAlarm:{addListener(){}},create:async()=>{}},
  tabs:{sendMessage:async ()=>({usagePage:true}),reload:async id=>out.push(id)}
};
vm.runInContext(fs.readFileSync(dir+'background.js','utf8'),vm.createContext({URL,chrome}));
(async()=>{
  const emit = (message,sender)=>new Promise(resolve=>{
    if (!handlers.message(message,{id:'verified',...sender},resolve)) resolve(undefined);
  });
  assert.equal(await emit({type:'watch'},{tab:{id:10},frameId:0,url:'https://chatgpt.com/c/something'}),undefined);
  for(const [provider,url,items,useMeters] of [examples[0],examples[2]]){
    const sender={tab:{id:provider==='chatgpt'?11:12},frameId:0,url};
    assert.equal((await emit({type:'watch'},sender)).ok,true);
    const snapshot=renderer.QuotaReader.extract(usagePage(items,useMeters),url,Date.now());
    assert.equal((await emit({type:'capture',snapshot},sender)).ok,true);
  }
  const result=await emit({type:'refresh'},{});
  assert.equal(result.refreshed,2);
  assert.deepEqual(out.slice(-2),[11,12]);
  assert.equal(out[1].payload.snapshots.length,2);
  assert.equal(data.local.linked,true);
  console.log('EN/ZH quota extraction, safe path checks, native bridge dispatch, and refresh checked with fixtures.');
})().catch(error=>{console.error(error);process.exitCode=1;});
