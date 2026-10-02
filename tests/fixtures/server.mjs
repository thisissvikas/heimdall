import http from 'node:http';
const jobs=new Map(),operations=new Map();
let sequence=0;
http.createServer(async (req,res)=> {
  const url=new URL(req.url,'http://fixture');
  const send=(code,body)=> { res.writeHead(code,{'Content-Type':'application/json'}); res.end(body===undefined?'':JSON.stringify(body)); };
  if(req.method==='POST'&&url.pathname==='/oauth/token') {
    let body=''; for await(const chunk of req) { body+=chunk; if(body.length>8192) return send(413,{error:'too_large'}); }
    const form=new URLSearchParams(body);
    if(form.get('client_id')!=='fixture-client'||form.get('client_secret')!=='fixture-client-secret') return send(401,{error:'invalid_client'});
    return send(200,{access_token:'fixture-access-token',token_type:'Bearer',expires_in:300});
  }
  if(url.pathname.startsWith('/jobs')&&req.headers.authorization!=='Bearer fixture-access-token') return send(401,{error:'unauthorized'});
  if(req.method==='POST'&&url.pathname==='/jobs') {
    if(req.headers['x-probe-key']!=='fixture-probe-key') return send(403,{error:'invalid_probe_key'});
    const key=req.headers['idempotency-key'];
    if(key&&operations.has(key)) return send(202,{id:operations.get(key)});
    const id=`job-${++sequence}`; jobs.set(id,{polls:0}); if(key) operations.set(key,id); return send(202,{id});
  }
  const parts=url.pathname.split('/'); const job=jobs.get(parts[2]);
  if(parts[1]==='jobs'&&job) {
    if(req.method==='DELETE') { jobs.delete(parts[2]); return send(204); }
    if(parts[3]==='items') return send(200,{items:[{requiredField:'present'}]});
    return send(200,{state:++job.polls>=3?'READY':'PROCESSING'});
  }
  if(url.pathname==='/health') return send(200,{ok:true});
  send(404,{error:'not_found'});
}).listen(8090,'0.0.0.0');
