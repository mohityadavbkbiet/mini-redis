import { useApp } from '../context/AppContext.jsx';
import { PageHeader, Panel } from '../components/Ui.jsx';
import { fmtBytes, fmtUptime } from '../format.js';

export default function Settings() {
  const { theme, setTheme, intervalMs, setIntervalMs, paused, setPaused, info, error } = useApp();
  return (
    <>
      <PageHeader title="Settings" subtitle="Preferences are saved in this browser." />
      <div className="grid-1-1">
        <Panel title="Appearance" icon="palette">
          <div className="seg" role="radiogroup" aria-label="Theme">
            {[['light', 'sun', 'Light'], ['dark', 'moon-stars', 'Dark']].map(([v, icon, label]) => (
              <button type="button" key={v} role="radio" aria-checked={theme === v} className={theme === v ? 'on' : ''} onClick={() => setTheme(v)}>
                <i className={`bi bi-${icon}`} /> {label}
              </button>
            ))}
          </div>
        </Panel>
        <Panel title="Live updates" icon="broadcast">
          <label className="form-label" htmlFor="interval">Refresh interval</label>
          <select id="interval" className="form-select mb-3" value={intervalMs} onChange={(e) => setIntervalMs(Number(e.target.value))}>
            <option value={1000}>Every 1 second</option>
            <option value={2000}>Every 2 seconds</option>
            <option value={5000}>Every 5 seconds</option>
          </select>
          <div className="form-check form-switch">
            <input className="form-check-input" type="checkbox" id="pause" checked={paused} onChange={(e) => setPaused(e.target.checked)} />
            <label className="form-check-label" htmlFor="pause">Pause live updates</label>
          </div>
        </Panel>
        <Panel title="Server" icon="hdd-network">
          <dl className="facts">
            <div><dt>Status</dt><dd>{error ? 'Offline' : info ? 'Online' : 'Connecting'}</dd></div>
            <div><dt>Uptime</dt><dd className="mono">{info ? fmtUptime(info.uptimeSeconds) : '-'}</dd></div>
            <div><dt>Redis port</dt><dd className="mono">{info?.redisPort ?? '-'}</dd></div>
            <div><dt>Web gateway port</dt><dd className="mono">{info?.httpPort ?? '-'}</dd></div>
            <div><dt>Persistence</dt><dd className="mono">{info ? (info.aofEnabled ? `AOF, fsync ${info.fsync}, ${fmtBytes(info.aofSizeBytes)}` : 'off') : '-'}</dd></div>
            <div><dt>Max clients</dt><dd className="mono">{info?.maxClients ?? '-'}</dd></div>
          </dl>
          <p className="small text-body-secondary mt-3 mb-0">Connect any Redis client to the Redis port, for example <code className="mono">redis-cli -p {info?.redisPort ?? 6380}</code>.</p>
        </Panel>
      </div>
    </>
  );
}
