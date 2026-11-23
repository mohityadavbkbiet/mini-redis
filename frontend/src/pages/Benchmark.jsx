import { useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../context/AppContext.jsx';
import { num } from '../format.js';
import { Empty, PageHeader, Panel } from '../components/Ui.jsx';

const PRESETS = [
  { label: 'Light', clients: 5, requests: 1000, valueBytes: 32 },
  { label: 'Typical', clients: 20, requests: 5000, valueBytes: 64 },
  { label: 'Heavy', clients: 50, requests: 5000, valueBytes: 256 },
];

export default function Benchmark() {
  const { toast, reload } = useApp();
  const [cfg, setCfg] = useState(PRESETS[1]);
  const [running, setRunning] = useState(false);
  const [runs, setRuns] = useState([]);
  const set = (k, v) => setCfg((c) => ({ ...c, [k]: Number(v) }));
  const total = cfg.clients * cfg.requests * 2;

  async function run() {
    setRunning(true);
    try {
      const r = await api.bench({ clients: cfg.clients, requests: cfg.requests, valueBytes: cfg.valueBytes });
      setRuns((x) => [{ ...r, at: new Date() }, ...x].slice(0, 8));
      reload();
    } catch (e) { toast(e.message, 'error'); }
    setRunning(false);
  }

  const last = runs[0];
  const best = Math.max(1, ...runs.map((r) => r.opsPerSec));

  return (
    <>
      <PageHeader title="Benchmark" subtitle="Real TCP clients hammer the server with SET and GET, and measure throughput and latency percentiles." />
      <div className="grid-2-3">
        <Panel title="Scenario" icon="sliders2">
          <div className="stack-sm">
            <div className="chips">
              {PRESETS.map((p) => (
                <button key={p.label} type="button" className={`chip${cfg.label === p.label ? ' on' : ''}`} onClick={() => setCfg(p)}>{p.label}</button>
              ))}
            </div>
            <div>
              <label className="form-label d-flex justify-content-between" htmlFor="b-clients">Parallel clients <b>{cfg.clients}</b></label>
              <input id="b-clients" type="range" className="form-range" min="1" max="50" value={cfg.clients} onChange={(e) => set('clients', e.target.value)} />
            </div>
            <div>
              <label className="form-label d-flex justify-content-between" htmlFor="b-req">Requests per client <b>{num(cfg.requests)}</b></label>
              <input id="b-req" type="range" className="form-range" min="100" max="20000" step="100" value={cfg.requests} onChange={(e) => set('requests', e.target.value)} />
            </div>
            <div>
              <label className="form-label d-flex justify-content-between" htmlFor="b-val">Value size <b>{cfg.valueBytes} bytes</b></label>
              <input id="b-val" type="range" className="form-range" min="8" max="4096" step="8" value={cfg.valueBytes} onChange={(e) => set('valueBytes', e.target.value)} />
            </div>
            <button type="button" className="btn btn-accent btn-lg w-100" disabled={running} onClick={run}>
              <i className={`bi bi-${running ? 'hourglass-split' : 'lightning-charge-fill'}`} /> {running ? `Running ${num(total)} requests...` : `Run (${num(total)} requests)`}
            </button>
            <p className="small text-body-secondary mb-0">Each client does SET then GET on its own keys. The benchmark runs inside the server process, so the browser is not the bottleneck, and its keys are deleted afterwards.</p>
          </div>
        </Panel>

        <div className="stack">
          {!last ? (
            <Panel><Empty icon="speedometer2" title="No results yet">Pick a scenario and press Run.</Empty></Panel>
          ) : (
            <>
              <div className="result-grid">
                <div className="result big"><b>{num(last.opsPerSec)}</b><span>operations / sec</span></div>
                <div className="result"><b>{last.avgMs} ms</b><span>average</span></div>
                <div className="result"><b>{last.p50Ms} ms</b><span>p50 (median)</span></div>
                <div className="result"><b>{last.p95Ms} ms</b><span>p95</span></div>
                <div className="result"><b>{last.p99Ms} ms</b><span>p99</span></div>
                <div className="result"><b>{last.maxMs} ms</b><span>slowest</span></div>
              </div>
              {last.errors > 0 && <div className="callout bad"><i className="bi bi-exclamation-octagon-fill" /><div><strong>{last.errors} errors during the run</strong></div></div>}
              <Panel title="Runs in this session" icon="clock-history">
                <div className="hbars">
                  {runs.map((r, i) => (
                    <div className="run-row" key={i}>
                      <span className="mono small">{r.clients} x {num(r.requests / r.clients / 2)}, {r.valueBytes}B</span>
                      <div className="track"><div className="fill avg" style={{ width: `${(r.opsPerSec / best) * 100}%` }} /><span>{num(r.opsPerSec)}/s</span></div>
                      <span className="small text-body-secondary">p99 {r.p99Ms} ms</span>
                    </div>
                  ))}
                </div>
              </Panel>
            </>
          )}
          <Panel title="How to read this" icon="lightbulb">
            <ul className="explain">
              <li><b>p50 / p95 / p99.</b> 99 percent of requests were faster than the p99 number. Tail latency matters more than the average in real systems.</li>
              <li><b>Why not compare with real Redis?</b> Redis is written in C with an event loop. This project optimises for clarity, so use it to see how design choices (value size, client count) change the numbers.</li>
            </ul>
          </Panel>
        </div>
      </div>
    </>
  );
}
