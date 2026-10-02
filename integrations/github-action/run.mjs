import {appendFile,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

export function passesGate(run) {
  return run.status==='passed'&&run.publication==='published'&&Array.isArray(run.outcomes)&&run.outcomes.length>0&&run.outcomes.every(r=>r.status==='passed'&&r.cleanup==='passed');
}
export function junit(run) {
  const escape=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
  const cases=run.outcomes.map(r=>`<testcase classname="${escape(r.monitorRef)}" name="${escape(r.location)}">${r.status==='passed'&&r.cleanup==='passed'?'':`<failure message="${escape(r.status)}"/>`}</testcase>`);
  if(!passesGate(run)&&!cases.some(c=>c.includes('<failure'))) cases.push('<testcase name="coverage"><failure message="Results incomplete"/></testcase>');
  return `<?xml version="1.0" encoding="UTF-8"?><testsuite name="Heimdall">${cases.join('')}</testsuite>`;
}
export async function gate(env,request=fetch,sleep=ms=>new Promise(r=>setTimeout(r,ms)),clock=()=>Date.now()) {
  const endpoint=new URL(env.HEIMDALL_ENDPOINT); if(endpoint.protocol!=='https:') throw new Error('API endpoint requires HTTPS');
  const oidc=new URL(env.ACTIONS_ID_TOKEN_REQUEST_URL); oidc.searchParams.set('audience','heimdall');
  const freshToken=async()=> {
    const response=await request(oidc,{headers:{Authorization:`Bearer ${env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}`},signal:AbortSignal.timeout(10000)});
    if(!response.ok) throw new Error('GitHub OIDC token unavailable');
    const {value}=await response.json(); if(typeof value!=='string'||!value) throw new Error('GitHub OIDC token unavailable'); return value;
  };
  const headers={Authorization:`Bearer ${await freshToken()}`,'Content-Type':'application/json','Idempotency-Key':`${env.GITHUB_RUN_ID}:${env.GITHUB_RUN_ATTEMPT}`};
  const creation=await request(new URL('/v1/runs',endpoint),{method:'POST',headers,signal:AbortSignal.timeout(10000),body:JSON.stringify({references:[env.HEIMDALL_REFERENCE],environment:env.HEIMDALL_ENVIRONMENT,locations:(env.HEIMDALL_LOCATIONS||'').split(',').filter(Boolean),parameters:{},deployment:{commit:env.GITHUB_SHA,repository:env.GITHUB_REPOSITORY,artifactDigest:env.HEIMDALL_ARTIFACT_DIGEST??null}})});
  if(creation.status!==202) throw new Error(`Run admission returned HTTP ${creation.status}`); const {id}=await creation.json();
  if(typeof id!=='string'||!/^[a-zA-Z0-9-]+$/.test(id)) throw new Error('Invalid run identifier');
  const timeout=Number(env.HEIMDALL_TIMEOUT||1800); if(!Number.isFinite(timeout)||timeout<=0||timeout>86400) throw new Error('Invalid gate timeout');
  const deadline=clock()+timeout*1000;
  while(clock()<deadline) {
    let response=await request(new URL(`/v1/runs/${id}`,endpoint),{headers,signal:AbortSignal.timeout(10000)});
    if(response.status===401) {
      headers.Authorization=`Bearer ${await freshToken()}`;
      response=await request(new URL(`/v1/runs/${id}`,endpoint),{headers,signal:AbortSignal.timeout(10000)});
    }
    if(!response.ok) throw new Error('Run status unavailable');
    const run=await response.json();
    if(['passed','failed','timed_out','cancelled','error'].includes(run.status)) return {id,run,passed:passesGate(run)};
    await sleep(1000);
  }
  throw new Error('Deployment gate timed out');
}
if(import.meta.url===pathToFileURL(process.argv[1]??'').href) {
  try {
    const result=await gate(process.env);
    if(process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT,`run-id=${result.id}\n`);
    await writeFile('heimdall-results.json',JSON.stringify(result.run,null,2)); await writeFile('heimdall-results.xml',junit(result.run));
    if(!result.passed) process.exitCode=1;
  } catch(error) { process.stderr.write(`${error.message}\n`); process.exitCode=1; }
}
