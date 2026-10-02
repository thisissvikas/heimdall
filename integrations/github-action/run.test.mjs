import {test} from 'node:test';
import assert from 'node:assert/strict';
import {gate,passesGate} from './run.mjs';
test('requires complete passed regional coverage and published results',()=> {
  for(const status of ['queued','waiting','failed','cancelled','error','timed_out']) assert.equal(passesGate({status,publication:'published',outcomes:[]}),false);
  assert.equal(passesGate({status:'passed',publication:'published',outcomes:[]}),false);
  assert.equal(passesGate({status:'passed',publication:'published',outcomes:[{status:'passed',cleanup:'passed'}]}),true);
});
test('refreshes expired OIDC tokens while waiting for long workflows',async()=> {
  let tokens=0,reads=0,submissions=0;
  const request=async(url,options)=> {
    if(String(url).includes('/token')) {tokens++;return {ok:true,json:async()=>({value:`token-${tokens}`})};}
    if(options.method==='POST') {submissions++;return {status:202,json:async()=>({id:'run-2'})};}
    if(++reads===1) return {ok:false,status:401};
    assert.equal(options.headers.Authorization,'Bearer token-2');
    return {ok:true,status:200,json:async()=>({status:'passed',publication:'published',outcomes:[{status:'passed',cleanup:'passed'}]})};
  };
  const result=await gate({HEIMDALL_ENDPOINT:'https://synthetics.example',ACTIONS_ID_TOKEN_REQUEST_URL:'https://github.example/token',ACTIONS_ID_TOKEN_REQUEST_TOKEN:'fixture',HEIMDALL_REFERENCE:'suite',HEIMDALL_ENVIRONMENT:'staging',GITHUB_RUN_ID:'2',GITHUB_RUN_ATTEMPT:'1'},request,async()=>{});
  assert.equal(result.passed,true);assert.equal(tokens,2);assert.equal(submissions,1);
});
test('uses OIDC and declared references, then waits for completion',async()=> {
  const calls=[]; let n=0;
  const request=async(url,options)=> { calls.push({url:String(url),options}); const data=[{value:'oidc-token'},{id:'run-1'},{status:'running'},{status:'passed',publication:'published',outcomes:[{status:'passed',cleanup:'passed'}]}][n++];return {ok:true,status:n===2?202:200,json:async()=>data}; };
  const result=await gate({HEIMDALL_ENDPOINT:'https://synthetics.example',ACTIONS_ID_TOKEN_REQUEST_URL:'https://github.example/token',ACTIONS_ID_TOKEN_REQUEST_TOKEN:'github-token',HEIMDALL_REFERENCE:'payments/checkout/deployment',HEIMDALL_ENVIRONMENT:'staging',GITHUB_RUN_ID:'1',GITHUB_RUN_ATTEMPT:'2'},request,async()=>{});
  assert.equal(result.passed,true); assert.equal(new URL(calls[0].url).searchParams.get('audience'),'heimdall');
  assert.deepEqual(JSON.parse(calls[1].options.body).references,['payments/checkout/deployment']); assert.equal(calls[1].options.headers['Idempotency-Key'],'1:2');
});
