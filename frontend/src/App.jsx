import { useEffect, useState } from 'react';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { useApp } from './context/AppContext.jsx';
import { Toasts } from './components/Ui.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Keys from './pages/Keys.jsx';
import Console from './pages/Console.jsx';
import Scenarios from './pages/Scenarios.jsx';
import Benchmark from './pages/Benchmark.jsx';
import Architecture from './pages/Architecture.jsx';
import Settings from './pages/Settings.jsx';
import NotFound from './pages/NotFound.jsx';

const NAV = [
  { section: 'Overview', items: [
    { to: '/', icon: 'speedometer2', label: 'Dashboard' },
    { to: '/keys', icon: 'key', label: 'Keys' },
  ] },
  { section: 'Tools', items: [
    { to: '/console', icon: 'terminal', label: 'Console' },
    { to: '/scenarios', icon: 'magic', label: 'Scenarios' },
    { to: '/benchmark', icon: 'lightning-charge', label: 'Benchmark' },
  ] },
  { section: 'Learn', items: [
    { to: '/architecture', icon: 'diagram-3', label: 'Architecture' },
    { to: '/settings', icon: 'sliders', label: 'Settings' },
  ] },
];

function titleFor(p) {
  const hit = NAV.flatMap((s) => s.items).find((i) => i.to === p);
  return hit ? hit.label : 'Not found';
}

function Sidebar({ open, onClose }) {
  const { pathname } = useLocation();
  const { info, error } = useApp();
  const up = info && !error;
  return (
    <>
      <aside className={`sidebar${open ? ' open' : ''}`}>
        <Link to="/" className="brand" onClick={onClose}>
          <span className="brand-mark"><i className="bi bi-database-fill" /></span>
          <span>
            <strong>Mini Redis</strong>
            <small>Key-value store console</small>
          </span>
        </Link>
        <nav>
          {NAV.map((sec) => (
            <div key={sec.section} className="nav-section">
              <div className="nav-title">{sec.section}</div>
              {sec.items.map((i) => (
                <Link key={i.to} to={i.to} onClick={onClose} className={`nav-item${pathname === i.to ? ' active' : ''}`}>
                  <i className={`bi bi-${i.icon}`} />{i.label}
                </Link>
              ))}
            </div>
          ))}
        </nav>
        <div className="side-foot">
          <div className={`backend-card ${up ? 'up' : error ? 'down' : ''}`}>
            <span className="dot" />
            <div>
              <strong>{up ? 'Server online' : error ? 'Server offline' : 'Connecting...'}</strong>
              <small>{up ? `redis :${info.redisPort} - ${info.keys} keys` : 'localhost:8081'}</small>
            </div>
          </div>
        </div>
      </aside>
      {open && <div className="scrim" onClick={onClose} />}
    </>
  );
}

function Topbar({ onMenu }) {
  const { pathname } = useLocation();
  const { theme, setTheme, error, paused, setPaused, intervalMs, reload } = useApp();
  const state = error ? 'offline' : paused ? 'paused' : 'live';
  return (
    <header className="topbar">
      <button type="button" className="btn btn-ghost menu-btn" onClick={onMenu} aria-label="Open menu"><i className="bi bi-list" /></button>
      <h2 className="topbar-title">{titleFor(pathname)}</h2>
      <div className="topbar-right">
        <button type="button" className={`live-chip ${state}`} onClick={() => setPaused(!paused)} title={paused ? 'Resume live updates' : 'Pause live updates'}>
          <span className="dot" />
          {state === 'live' ? `Live - ${intervalMs / 1000}s` : state === 'paused' ? 'Paused' : 'Offline'}
        </button>
        <button type="button" className="btn btn-ghost icon-btn" onClick={reload} title="Refresh now" aria-label="Refresh now"><i className="bi bi-arrow-clockwise" /></button>
        <button type="button" className="btn btn-ghost icon-btn" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')} title="Toggle theme" aria-label="Toggle theme">
          <i className={`bi bi-${theme === 'dark' ? 'sun' : 'moon-stars'}`} />
        </button>
        <Link to="/console" className="btn btn-accent d-none d-sm-inline-flex"><i className="bi bi-terminal" /> Console</Link>
      </div>
    </header>
  );
}

export default function App() {
  const [menu, setMenu] = useState(false);
  const { pathname } = useLocation();
  const { error } = useApp();
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);

  return (
    <div className="shell">
      <Sidebar open={menu} onClose={() => setMenu(false)} />
      <div className="main">
        <Topbar onMenu={() => setMenu(true)} />
        <main className="content">
          {error && (
            <div className="offline-banner" role="alert">
              <i className="bi bi-plug" />
              <div>
                <strong>Cannot reach the Mini Redis server.</strong>
                <span> Start it in another terminal with <code>bash run.sh</code> (web gateway on port 8081); this page recovers on its own.</span>
              </div>
            </div>
          )}
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/keys" element={<Keys />} />
            <Route path="/console" element={<Console />} />
            <Route path="/scenarios" element={<Scenarios />} />
            <Route path="/benchmark" element={<Benchmark />} />
            <Route path="/architecture" element={<Architecture />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
      </div>
      <Toasts />
    </div>
  );
}
