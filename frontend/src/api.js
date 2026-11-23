const BASE = '/api';

async function req(path, opts = {}) {
  let res;
  try {
    res = await fetch(BASE + path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  } catch {
    throw new Error('Cannot reach the Mini Redis web gateway. Is the server running? (bash run.sh, HTTP port 8081)');
  }
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

const post = (path, body) => req(path, { method: 'POST', body: JSON.stringify(body) });

export const api = {
  info: () => req('/info'),
  keys: (pattern) => req(`/keys?limit=1000&pattern=${encodeURIComponent(pattern || '*')}`).then((d) => ({ ...d, at: Date.now() })),
  key: (name) => req(`/key?name=${encodeURIComponent(name)}`).then((d) => ({ ...d, at: Date.now() })),
  /** Runs one command given as an array of arguments (no quoting problems). */
  exec: (args) => post('/exec', { args }),
  /** Runs one command typed like in redis-cli, e.g. SET a "hello world". */
  command: (command) => post('/exec', { command }),
  bench: (body) => post('/bench', body),
};
