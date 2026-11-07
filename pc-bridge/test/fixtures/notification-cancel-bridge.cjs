'use strict';
// Two isolated QA bridges with the real runtime; no CLI, files or model providers.
const http = require('node:http');
const fs = require('node:fs');
const {SubagentRuntime} = require('../../lib/subagent-runtime');
const records = {};
function save() {
  fs.writeFileSync('uploads/notification-cancel-bridge-state.json', JSON.stringify(records, null, 2));
}
for (const [name, port] of [['A',8810],['B',8811]]) {
  const holdDispatch = name === 'A' && process.argv.includes('--hold-dispatch-a');
  let releaseDispatch;
  let cleaningUp = false;
  const dispatchGate = new Promise(resolve => { releaseDispatch = resolve; });
  const record = records[name] = {started:0, aborted:0, cancels:[], batchId:'', statuses:[], active:0,
    holdDispatch, dispatchReceived:0};
  const runtime = new SubagentRuntime({timeoutMs:120000, run: ({signal}) => new Promise((resolve,reject) => {
    record.started++; save();
    const abort = () => { record.aborted++; save(); reject(new Error('QA worker aborted')); };
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, {once:true});
  }), onChange: snapshot => {
    record.batchId = snapshot.batchId;
    record.statuses = snapshot.agents.map(a => a.status);
    record.active = snapshot.agents.filter(a => a.settling || a.status==='running' || a.status==='queued').length;
    save();
  }});
  http.createServer(async (req,res) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`);
    const reply = (data, status=200) => {res.writeHead(status, {'Content-Type':'application/json'});res.end(JSON.stringify(data));};
    try {
      if (req.method==='GET' && url.pathname==='/qa/state') return reply({...record, swarm:runtime.snapshot()});
      if (req.method==='GET' && url.pathname==='/api/agents/swarm') return reply({ok:true,swarm:runtime.snapshotFor(url.searchParams.get('requestId')||'')});
      if (req.method==='POST' && url.pathname==='/qa/release-dispatch') {releaseDispatch(); return reply({ok:true});}
      if (req.method==='POST' && url.pathname==='/qa/cleanup') {
        cleaningUp=true; releaseDispatch(); runtime.cancel(); return reply({ok:true});
      }
      if (req.method==='POST' && url.pathname==='/api/agents/swarm') {
        let raw='';for await (const chunk of req) {raw+=chunk;if(raw.length>20000) throw Error('QA request too large');}
        const body=JSON.parse(raw);
        if (!body.requestId) return reply({ok:false,error:'requestId required'},400);
        if (body.action==='cancel_task') {
          record.cancels.push(body.requestId);save();
          const snapshot=runtime.cancel(body.requestId);
          if(name==='A') await new Promise(r=>setTimeout(r,1500));
          return reply({ok:true,swarm:snapshot});
        }
        if (body.action==='dispatch_task') {
          record.dispatchReceived++;save();
          if (holdDispatch) {
            let timer;
            try { await Promise.race([dispatchGate,new Promise((_,reject) => {
              timer=setTimeout(() => reject(Error('QA dispatch gate timed out')),30000);
            })]); } finally { clearTimeout(timer); }
          }
          if (cleaningUp) return reply({ok:false},410);
          return reply({ok:true,swarm:runtime.dispatch({task:body.task,model:body.model,requestId:body.requestId})});
        }
        return reply({ok:false},400);
      }
      if (req.method==='GET') return reply({ok:true,hostname:`QA bridge ${name}`,instanceName:`QA ${name}`,
        sessions:[],models:[],agents:[],plugins:[],skills:[],servers:[],connections:[]});
      return reply({ok:false},405);
    } catch(e) {reply({ok:false,error:e.message},500);}
  }).listen(port,'127.0.0.1');
}
