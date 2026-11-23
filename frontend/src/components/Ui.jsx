import { useState } from 'react';
import { useApp } from '../context/AppContext.jsx';

export function PageHeader({ title, subtitle, children }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      <div className="page-actions">{children}</div>
    </div>
  );
}

export function Panel({ title, icon, right, children, flush, className = '' }) {
  return (
    <section className={`panel ${className}`}>
      {(title || right) && (
        <header className="panel-head">
          <h2>{icon && <i className={`bi bi-${icon}`} />}{title}</h2>
          <div>{right}</div>
        </header>
      )}
      <div className={flush ? '' : 'panel-body'}>{children}</div>
    </section>
  );
}

export function KpiCard({ icon, label, value, hint, tone = 'accent', children }) {
  return (
    <div className={`kpi tone-${tone}`}>
      <div className="kpi-top">
        <span className="kpi-icon"><i className={`bi bi-${icon}`} /></span>
        <span className="kpi-label">{label}</span>
      </div>
      <div className="kpi-value">{value}</div>
      <div className="kpi-hint">{hint}</div>
      <div className="kpi-spark">{children}</div>
    </div>
  );
}

export function Empty({ icon = 'inbox', title, children }) {
  return (
    <div className="empty">
      <i className={`bi bi-${icon}`} />
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  );
}

export function Skeleton({ rows = 4 }) {
  return (
    <div className="skeleton-wrap">
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" style={{ width: `${95 - i * 9}%` }} />)}
    </div>
  );
}

export function CopyButton({ text, label }) {
  const [done, setDone] = useState(false);
  async function copy() {
    try { await navigator.clipboard.writeText(text); } catch { /* clipboard blocked: ignore */ }
    setDone(true);
    setTimeout(() => setDone(false), 1500);
  }
  return (
    <button type="button" className="btn btn-ghost btn-sm" onClick={copy} title="Copy">
      <i className={`bi bi-${done ? 'check2' : 'clipboard'}`} /> {done ? 'Copied' : label || ''}
    </button>
  );
}

export function Toasts() {
  const { toasts, dismiss } = useApp();
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast-card ${t.kind}`} role="status">
          <i className={`bi bi-${t.kind === 'error' ? 'exclamation-octagon-fill' : 'check-circle-fill'}`} />
          <span>{t.message}</span>
          <button type="button" className="btn-close btn-close-sm" aria-label="Dismiss" onClick={() => dismiss(t.id)} />
        </div>
      ))}
    </div>
  );
}
