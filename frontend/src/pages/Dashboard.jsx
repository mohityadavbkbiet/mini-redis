import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useApp } from '../context/AppContext.jsx';
import { useNow, usePolling } from '../usePolling.js';
import { TYPES, TYPE_ICON, fmtBytes, fmtUptime, num, relTime } from '../format.js';
import { Empty, KpiCard, PageHeader, Panel, Skeleton } from '../components/Ui.jsx';
import { AreaChart, Donut, Sparkline } from '../components/Charts.jsx';
import TtlBar from '../components/TtlBar.jsx';

export default function Dashboard() {
  const { info, samples } = useApp();
  const keys = usePolling(() => api.keys('*'), 3000);
  const now = useNow(1000);

  if (!info) return (<><PageHeader title="Dashboard" subtitle="Live view of the key-value store" /><Panel><Skeleton rows={6} /></Panel></>);

  const ops = samples.map((s) => s.ops);
  const cur = ops.length ? ops[ops.length - 1] : 0;
  const peak = Math.max(0, ...ops);
  const klist = keys.data?.keys || [];
  const byType = Object.fromEntries(TYPES.map((t) => [t, klist.filter((k) => k.type === t).length]));
  const expiring = keys.data
    ? klist.filter((k) => k.ttlMs >= 0).map((k) => ({ ...k, left: k.ttlMs - (now - keys.data.at) }))
        .filter((k) => k.left > 0).sort((a, b) => a.left - b.left).slice(0, 6)
    : [];
  const cmds = Object.entries(info.commandCounts).slice(0, 7);
  const maxCmd = Math.max(1, ...cmds.map(([, n]) => n));

  return (
    <>
      <PageHeader title="Dashboard" subtitle="Live view of the key-value store, refreshed automatically.">
        <Link to="/benchmark" className="btn btn-outline-soft"><i className="bi bi-lightning-charge" /> Run benchmark</Link>
        <Link to="/keys" className="btn btn-accent"><i className="bi bi-key" /> Browse keys</Link>
      </PageHeader>

      <div className="kpi-grid">
        <KpiCard icon="key" label="Keys" value={num(info.keys)} tone="green" hint={`${info.rawEntries - info.keys} expired, waiting for sweep`}>
          <Sparkline data={samples.map((s) => s.keys)} color="var(--c-SUCCESS)" />
        </KpiCard>
        <KpiCard icon="speedometer" label="Operations / sec" value={num(cur)} tone="violet" hint={`peak ${num(peak)}, ${num(info.totalCommands)} total`}>
          <Sparkline data={ops} color="var(--accent)" />
        </KpiCard>
        <KpiCard icon="people" label="Connected clients" value={info.connectedClients} tone="blue" hint={`${num(info.totalConnections)} connections so far`}>
          <Sparkline data={samples.map((s) => s.clients)} color="var(--c-RUNNING)" />
        </KpiCard>
        <KpiCard icon="hdd" label="AOF log" tone="amber" value={info.aofEnabled ? fmtBytes(info.aofSizeBytes) : 'off'}
                 hint={info.aofEnabled ? `fsync ${info.fsync}, uptime ${fmtUptime(info.uptimeSeconds)}` : `persistence disabled, uptime ${fmtUptime(info.uptimeSeconds)}`}>
          <Sparkline data={samples.map((s) => s.keys)} color="var(--c-RETRYING)" />
        </KpiCard>
      </div>

      <div className="grid-2-1">
        <Panel title="Operations per second" icon="graph-up" right={<span className="legend">last {samples.length} samples</span>}>
          <AreaChart data={ops} unit="ops/s" />
        </Panel>
        <Panel title="Keys by type" icon="pie-chart">
          <Donut center={klist.length} sub="keys" segments={TYPES.map((t) => ({ label: t, value: byType[t], color: `var(--t-${t})` }))} />
          <ul className="mix-list">
            {TYPES.map((t) => (
              <li key={t}><i className="sw" style={{ background: `var(--t-${t})` }} /><i className={`bi bi-${TYPE_ICON[t]}`} />{t}<b>{byType[t]}</b></li>
            ))}
          </ul>
        </Panel>
      </div>

      <div className="grid-3">
        <Panel title="Top commands" icon="bar-chart-steps">
          {cmds.length === 0 ? <Empty icon="terminal" title="No commands yet">Open the <Link to="/console">console</Link> and run one.</Empty> : (
            <div className="cmd-bars">
              {cmds.map(([name, n]) => (
                <div key={name} className="cmd-bar">
                  <span className="mono">{name}</span>
                  <div className="track"><div className="fill avg" style={{ width: `${(n / maxCmd) * 100}%` }} /></div>
                  <b>{num(n)}</b>
                </div>
              ))}
            </div>
          )}
        </Panel>
        <Panel title="Expiring soon" icon="hourglass-split" right={<Link to="/keys" className="small">All keys</Link>}>
          {expiring.length === 0 ? <Empty icon="infinity" title="Nothing expiring">Keys with a TTL show up here with a live countdown.</Empty> : (
            <ul className="expiring">
              {expiring.map((k) => (
                <li key={k.key}>
                  <Link to={`/keys?k=${encodeURIComponent(k.key)}`} className="mono id">{k.key}</Link>
                  <TtlBar leftMs={k.left} />
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Recent commands" icon="broadcast">
          {info.recent.length === 0 ? <p className="text-body-secondary mb-0">Nothing yet.</p> : (
            <ol className="recent">
              {info.recent.map((r, i) => (
                <li key={i}><code className="mono">{r.text}</code><span>{relTime(r.at, info.now)}</span></li>
              ))}
            </ol>
          )}
        </Panel>
      </div>
    </>
  );
}
