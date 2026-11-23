import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { PageHeader, Panel } from '../components/Ui.jsx';

const REF = [
  { group: 'Strings', items: [
    ['SET key value [EX s]', 'SET greeting "hello world" EX 60'], ['GET key', 'GET greeting'], ['INCR key', 'INCR visits'],
    ['APPEND key text', 'APPEND greeting "!"'], ['MSET k v [k v ...]', 'MSET a 1 b 2'], ['MGET k [k ...]', 'MGET a b nope'],
  ] },
  { group: 'Keys and expiry', items: [
    ['DEL key [key ...]', 'DEL a b'], ['EXISTS key', 'EXISTS greeting'], ['KEYS pattern', 'KEYS *'], ['TYPE key', 'TYPE greeting'],
    ['EXPIRE key seconds', 'EXPIRE greeting 30'], ['TTL key', 'TTL greeting'], ['PERSIST key', 'PERSIST greeting'], ['DBSIZE', 'DBSIZE'],
  ] },
  { group: 'Lists', items: [
    ['RPUSH key v [v ...]', 'RPUSH jobs build test deploy'], ['LPUSH key v', 'LPUSH jobs urgent'], ['LPOP key', 'LPOP jobs'],
    ['LRANGE key start stop', 'LRANGE jobs 0 -1'], ['LLEN key', 'LLEN jobs'],
  ] },
  { group: 'Hashes', items: [
    ['HSET key f v [f v ...]', 'HSET user:1 name Mohit city Delhi'], ['HGET key field', 'HGET user:1 name'],
    ['HGETALL key', 'HGETALL user:1'], ['HDEL key field', 'HDEL user:1 city'],
  ] },
  { group: 'Server', items: [['PING', 'PING'], ['INFO', 'INFO'], ['BGREWRITEAOF', 'BGREWRITEAOF']] },
];
const NAMES = [...new Set(REF.flatMap((g) => g.items.map(([u]) => u.split(' ')[0])).concat(['FLUSHALL', 'STRLEN', 'DECR', 'INCRBY', 'DECRBY', 'PEXPIRE', 'PTTL', 'HLEN', 'RPOP', 'ECHO']))].sort();

const FIRST = { kind: 'info', display: 'Type a command and press Enter. Up and down arrows recall history, Tab completes the command name, "clear" empties the screen.' };

export default function Console() {
  const [input, setInput] = useState('');
  const [log, setLog] = useState([FIRST]);
  const [history, setHistory] = useState([]);
  const [pos, setPos] = useState(-1);
  const [busy, setBusy] = useState(false);
  const endRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => { endRef.current?.scrollIntoView({ block: 'end' }); }, [log]);

  async function submit(e) {
    e.preventDefault();
    const cmd = input.trim();
    if (!cmd || busy) return;
    setInput('');
    setPos(-1);
    setHistory((h) => [cmd, ...h.filter((x) => x !== cmd)].slice(0, 50));
    if (cmd.toLowerCase() === 'clear') { setLog([]); return; }
    setBusy(true);
    try {
      const r = await api.command(cmd);
      setLog((l) => [...l, { cmd, ...r }]);
    } catch (err) {
      setLog((l) => [...l, { cmd, kind: 'error', display: `(error) ${err.message}` }]);
    }
    setBusy(false);
    inputRef.current?.focus();
  }

  function onKeyDown(e) {
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      const n = Math.min(history.length - 1, pos + 1);
      if (n >= 0) { setPos(n); setInput(history[n]); }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      const n = pos - 1;
      setPos(n);
      setInput(n >= 0 ? history[n] : '');
    } else if (e.key === 'Tab') {
      e.preventDefault();
      const word = input.toUpperCase();
      if (input && !input.includes(' ')) {
        const m = NAMES.filter((x) => x.startsWith(word));
        if (m.length === 1) setInput(m[0] + ' ');
        else if (m.length > 1) setLog((l) => [...l, { kind: 'info', display: m.join('  ') }]);
      }
    }
  }

  return (
    <>
      <PageHeader title="Console" subtitle="A redis-cli style terminal in the browser. Every command goes to the real server, and writes are logged to the AOF." />
      <div className="grid-3-2 console-grid">
        <div className="console">
          <div className="console-out" onClick={() => inputRef.current?.focus()}>
            {log.map((l, i) => (
              <div key={i} className="console-entry">
                {l.cmd && <div className="console-cmd"><span className="prompt">mini-redis&gt;</span> {l.cmd}</div>}
                <pre className={`console-reply k-${l.kind}`}>{l.display}</pre>
                {l.micros != null && <span className="console-time">{l.micros} &micro;s</span>}
              </div>
            ))}
            <div ref={endRef} />
          </div>
          <form onSubmit={submit} className="console-in">
            <span className="prompt">mini-redis&gt;</span>
            <input ref={inputRef} className="mono" value={input} autoFocus spellCheck="false" autoComplete="off" aria-label="Command"
                   onChange={(e) => setInput(e.target.value)} onKeyDown={onKeyDown} placeholder="SET name mohit" />
          </form>
        </div>

        <Panel title="Command reference" icon="book" flush>
          <div className="ref">
            {REF.map((g) => (
              <div key={g.group}>
                <div className="ref-title">{g.group}</div>
                {g.items.map(([usage, example]) => (
                  <button type="button" key={usage} className="ref-item" onClick={() => { setInput(example); inputRef.current?.focus(); }}>
                    <code className="mono">{usage}</code>
                    <span>{example}</span>
                  </button>
                ))}
              </div>
            ))}
          </div>
        </Panel>
      </div>
    </>
  );
}
