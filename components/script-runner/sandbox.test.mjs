import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const sandbox = new URL('./sandbox.mjs', import.meta.url).pathname;
function run(source, text='{"items":[{"id":1}]}') {
  const p=spawnSync(process.execPath,['--permission',`--allow-fs-read=${sandbox}`,sandbox],{
    input: JSON.stringify({source,response:{status:200,headers:{},text},vars:{}}),timeout:3000,maxBuffer:100000});
  return JSON.parse(p.stdout.toString());
}
test('evaluates assertions and declared output values',()=> {
  const r=run('export default function({response,assert}) { assert(response.json().items.every(i=>i.id!=null),"ids"); return {count:response.json().items.length}; }');
  assert.deepEqual(r,{assertions:[{passed:true,message:'ids'}],outputs:{count:1}});
});
test('reports failed collection assertions',()=>assert.equal(run('export default function({response,assert}) { assert(response.json().items.every(i=>i.id!=null),"ids"); }','{"items":[{"id":null}]}').assertions[0].passed,false));
test('bounds infinite loops',()=>assert.ok(run('export default function() { while(true) {} }').error));
test('cannot read process credentials, files or call fetch',()=> {
  for(const expression of ['process.env','require("node:fs")','fetch("https://example.com")','Function("return process")()','({}).constructor.constructor("return process")()']) {
    assert.ok(run(`export default function() { return ${expression}; }`).error);
  }
});
test('rejects malformed JSON and excessive output',()=> {
  assert.ok(run('export default function({response}) { return response.json(); }','not-json').error);
  assert.ok(run('export default function() { return {x:"a".repeat(70000)}; }').error);
});
