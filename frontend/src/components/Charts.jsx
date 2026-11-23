import { useId } from 'react';

export function Sparkline({ data, color = 'var(--accent)', height = 40 }) {
  const id = useId().replace(/:/g, '');
  if (!data || data.length === 0) return <div style={{ height }} />;
  if (data.length === 1) data = [data[0], data[0]]; // first sample: draw a flat line
  const max = Math.max(...data, 1), w = 100, h = height;
  const pts = data.map((v, i) => [(i / (data.length - 1)) * w, h - 3 - (v / max) * (h - 8)]);
  const line = pts.map((p) => p.join(',')).join(' ');
  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="spark" style={{ height }} aria-hidden="true">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity=".32" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <polygon points={`0,${h} ${line} ${w},${h}`} fill={`url(#${id})`} />
      <polyline points={line} fill="none" stroke={color} strokeWidth="1.8" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** Filled line chart with a y axis. data = array of numbers (oldest first). */
export function AreaChart({ data, color = 'var(--accent)', unit = '' }) {
  const id = useId().replace(/:/g, '');
  const W = 640, H = 200, L = 34, B = 22, T = 10;
  const pts = data.length ? data : [0];
  const max = Math.max(4, Math.ceil(Math.max(...pts) * 1.15));
  const n = Math.max(pts.length - 1, 1);
  const x = (i) => L + (i / n) * (W - L - 6);
  const y = (v) => T + (H - T - B) * (1 - v / max);
  const line = pts.map((v, i) => `${x(i)},${y(v)}`).join(' ');
  const ticks = [0, Math.round(max / 2), max];
  const last = pts[pts.length - 1];
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label="Operations per second over time">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity=".35" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={L} x2={W - 6} y1={y(t)} y2={y(t)} className="grid-line" />
          <text x={L - 6} y={y(t) + 4} textAnchor="end" className="axis-text">{t}</text>
        </g>
      ))}
      <polygon points={`${x(0)},${H - B} ${line} ${x(pts.length - 1)},${H - B}`} fill={`url(#${id})`} />
      <polyline points={line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" />
      <circle cx={x(pts.length - 1)} cy={y(last)} r="4" fill={color} />
      <text x={L} y={H - 5} className="axis-text">older</text>
      <text x={W - 6} y={H - 5} textAnchor="end" className="axis-text">now ({last} {unit})</text>
    </svg>
  );
}

export function Donut({ segments, center, sub }) {
  const total = segments.reduce((s, x) => s + x.value, 0);
  let offset = 25; // start at 12 o'clock
  return (
    <div className="donut-wrap">
      <svg viewBox="0 0 42 42" className="donut" role="img" aria-label="Jobs by status">
        <circle cx="21" cy="21" r="15.9155" fill="none" stroke="var(--surface-2)" strokeWidth="5" />
        {total > 0 && segments.filter((s) => s.value > 0).map((s) => {
          const pct = (s.value / total) * 100;
          const el = (
            <circle key={s.label} cx="21" cy="21" r="15.9155" fill="none" stroke={s.color} strokeWidth="5"
                    strokeDasharray={`${pct} ${100 - pct}`} strokeDashoffset={offset}>
              <title>{`${s.label}: ${s.value}`}</title>
            </circle>
          );
          offset -= pct;
          return el;
        })}
        <text x="21" y="21.5" textAnchor="middle" className="donut-n">{center}</text>
        <text x="21" y="27" textAnchor="middle" className="donut-s">{sub}</text>
      </svg>
    </div>
  );
}
