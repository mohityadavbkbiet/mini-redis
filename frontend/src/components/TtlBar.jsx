import { fmtTtl } from '../format.js';

/** Countdown text plus a bar that drains as the key approaches expiry (scale: 60s max). */
export default function TtlBar({ leftMs }) {
  if (leftMs == null || leftMs < 0) return <span className="text-body-secondary">no expiry</span>;
  const pct = Math.max(2, Math.min(100, (leftMs / 60000) * 100));
  return (
    <div className="ttl">
      <span className="mono">{fmtTtl(leftMs)}</span>
      <div className="ttl-bar"><div style={{ width: `${pct}%` }} className={leftMs < 10000 ? 'low' : ''} /></div>
    </div>
  );
}
