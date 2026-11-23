import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useApp } from '../context/AppContext.jsx';
import { PageHeader } from '../components/Ui.jsx';

const SCENARIOS = [
  { id: 'cache', icon: 'lightning-charge', title: 'Cache with expiry', use: 'Speed up a slow database',
    blurb: 'Store a computed result for a few seconds. When the TTL runs out the key disappears and the next request recomputes it.',
    steps: ['SET demo:cache:user:42 "{\\"name\\":\\"Mohit\\"}" EX 2', 'GET demo:cache:user:42', 'TTL demo:cache:user:42', { wait: 2600, label: 'waiting 2.6 s for the key to expire...' }, 'GET demo:cache:user:42', 'TTL demo:cache:user:42'] },
  { id: 'counter', icon: 'graph-up-arrow', title: 'Atomic page-view counter', use: 'Counting without race conditions',
    blurb: 'INCR is atomic: a thousand clients can count at once and no update is lost, because each key is locked while it changes.',
    steps: ['SET demo:views 0', 'INCR demo:views', 'INCR demo:views', 'INCRBY demo:views 10', 'GET demo:views'] },
  { id: 'queue', icon: 'list-ol', title: 'Job queue', use: 'Hand work to background workers',
    blurb: 'Producers RPUSH jobs on the right, workers LPOP from the left: a first-in-first-out queue in two commands.',
    steps: ['RPUSH demo:jobs build test deploy', 'LLEN demo:jobs', 'LPOP demo:jobs', 'LRANGE demo:jobs 0 -1', 'LPUSH demo:jobs urgent-fix', 'LRANGE demo:jobs 0 -1'] },
  { id: 'profile', icon: 'person-badge', title: 'User profile (hash)', use: 'Store an object field by field',
    blurb: 'A hash keeps related fields under one key, so you can read or change one field without rewriting the whole object.',
    steps: ['HSET demo:user:7 name Mohit city Delhi lang Java', 'HGET demo:user:7 city', 'HSET demo:user:7 city Pilani', 'HGETALL demo:user:7', 'HDEL demo:user:7 lang', 'HLEN demo:user:7'] },
  { id: 'session', icon: 'shield-lock', title: 'Login session', use: 'Sessions that log themselves out',
    blurb: 'A session token with a TTL. Activity extends it with EXPIRE; PERSIST makes it permanent (a "remember me" login).',
    steps: ['SET demo:session:abc123 user-7 EX 30', 'TTL demo:session:abc123', 'EXPIRE demo:session:abc123 600', 'TTL demo:session:abc123', 'PERSIST demo:session:abc123', 'TTL demo:session:abc123'] },
  { id: 'rate', icon: 'speedometer', title: 'Rate limiter', use: 'Allow N requests per window',
    blurb: 'Count requests per client in a key that expires after the window. When the count passes the limit, reject the request.',
    steps: ['INCR demo:rate:1.2.3.4', 'EXPIRE demo:rate:1.2.3.4 10', 'INCR demo:rate:1.2.3.4', 'INCR demo:rate:1.2.3.4', 'GET demo:rate:1.2.3.4', 'TTL demo:rate:1.2.3.4'] },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export default function Scenarios() {
  const { toast } = useApp();
  const [runs, setRuns] = useState({}); // id -> { status, lines: [] }

  async function run(sc) {
    setRuns((r) => ({ ...r, [sc.id]: { status: 'running', lines: [] } }));
    const push = (line) => setRuns((r) => ({ ...r, [sc.id]: { ...r[sc.id], lines: [...r[sc.id].lines, line] } }));
    try {
      for (const step of sc.steps) {
        if (typeof step === 'object') { push({ wait: step.label }); await sleep(step.wait); continue; }
        const res = await api.command(step);
        push({ cmd: step, kind: res.kind, display: res.display });
        await sleep(180);
      }
      setRuns((r) => ({ ...r, [sc.id]: { ...r[sc.id], status: 'done' } }));
    } catch (e) {
      toast(e.message, 'error');
      setRuns((r) => ({ ...r, [sc.id]: { ...r[sc.id], status: 'error' } }));
    }
  }

  async function cleanup() {
    try {
      const k = await api.keys('demo:*');
      if (k.keys.length) await api.exec(['DEL', ...k.keys.map((x) => x.key)]);
      setRuns({});
      toast(`Removed ${k.keys.length} demo keys`);
    } catch (e) { toast(e.message, 'error'); }
  }

  return (
    <>
      <PageHeader title="Scenarios" subtitle="Real-world patterns you can run with one click. Each one sends actual commands to the server and shows every reply.">
        <Link to="/keys?q=demo:*" className="btn btn-outline-soft"><i className="bi bi-key" /> See demo keys</Link>
        <button type="button" className="btn btn-outline-soft" onClick={cleanup}><i className="bi bi-trash" /> Remove demo keys</button>
      </PageHeader>

      <div className="scenario-grid">
        {SCENARIOS.map((sc) => {
          const r = runs[sc.id];
          return (
            <section key={sc.id} className="scenario">
              <header>
                <span className="scenario-icon"><i className={`bi bi-${sc.icon}`} /></span>
                <div><h2>{sc.title}</h2><small>{sc.use}</small></div>
              </header>
              <p>{sc.blurb}</p>
              {r && (
                <div className="mini-console" aria-live="polite">
                  {r.lines.map((l, i) => l.wait
                    ? <div key={i} className="mc-wait">{l.wait}</div>
                    : <div key={i}><div className="mc-cmd"><span className="prompt">&gt;</span> {l.cmd}</div><pre className={`console-reply k-${l.kind}`}>{l.display}</pre></div>)}
                </div>
              )}
              <div className="scenario-actions">
                <button type="button" className="btn btn-accent btn-sm" disabled={r?.status === 'running'} onClick={() => run(sc)}>
                  <i className={`bi bi-${r?.status === 'running' ? 'hourglass-split' : 'play-fill'}`} /> {r?.status === 'running' ? 'Running...' : r ? 'Run again' : 'Run'}
                </button>
                <span className="small text-body-secondary">{sc.steps.filter((s) => typeof s === 'string').length} commands</span>
              </div>
            </section>
          );
        })}
      </div>
    </>
  );
}
