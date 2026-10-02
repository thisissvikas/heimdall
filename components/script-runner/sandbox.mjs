import vm from 'node:vm';

// This process is a protocol worker, not the isolation boundary. Production runs it in gVisor.
let input = '';
for await (const chunk of process.stdin) {
  input += chunk;
  if (Buffer.byteLength(input) > 2_097_152) throw new Error('Input limit exceeded');
}
try {
  const payload = JSON.parse(input);
  if (typeof payload.source !== 'string' || payload.source.length > 65_536) throw new Error('Invalid source');
  const source = payload.source.replace(/export\s+default\s+function/, 'globalThis.__test = function');
  if (source === payload.source) throw new Error('Default function required');
  const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false } });
  const bootstrap = `
    const data = JSON.parse(${JSON.stringify(JSON.stringify({response: payload.response, vars: payload.vars}))});
    const assertions = [];
    const assert = (passed, message) => {
      if (assertions.length >= 100) throw new Error('Assertion limit exceeded');
      assertions.push({passed: Boolean(passed), message: String(message ?? 'script assertion').slice(0, 256)});
    };
    const response = Object.freeze({status: data.response.status, headers: Object.freeze(data.response.headers),
      text: () => data.response.text, json: () => JSON.parse(data.response.text)});
    ${source}
    const outputs = __test({response, vars: data.vars, assert}) ?? {};
    if (outputs && typeof outputs.then === 'function') throw new Error('Async scripts unsupported');
    globalThis.result = JSON.stringify({assertions, outputs});
  `;
  new vm.Script(bootstrap).runInContext(context, { timeout: 500 });
  const result = context.result;
  if (typeof result !== 'string' || Buffer.byteLength(result) > 65_536) throw new Error('Output limit exceeded');
  process.stdout.write(result);
} catch {
  process.stdout.write(JSON.stringify({error: 'Script failed or exceeded its limits'}));
  process.exitCode = 1;
}
