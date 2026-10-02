import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createService } from './service.mjs';

test('sandbox service executes bounded scripts and isolates failures between requests', async () => {
  const server = createService();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/evaluate`;
  const invoke = source => fetch(url, {method:'POST', body: JSON.stringify({source, response:{status:200,headers:{},text:'{}'},vars:{}})}).then(r => r.json());
  try {
    const result = await invoke('export default function({assert}) { assert(true); return {count:1}; }');
    assert.equal(result.outputs.count, 1);
    assert.equal(result.assertions[0].passed, true);
    assert.ok((await invoke('export default function() { while(true) {} }')).error);
    assert.ok((await invoke('export default function() { return {secret: process.env.PATH}; }')).error);
    assert.equal((await invoke('export default function() { return {ok:true}; }')).outputs.ok, true);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
