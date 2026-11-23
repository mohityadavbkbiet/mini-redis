export const TYPES = ['string', 'list', 'hash'];
export const TYPE_ICON = { string: 'fonts', list: 'list-ol', hash: 'braces' };

export function fmtBytes(n) {
  if (n == null || n < 0) return '-';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export function fmtUptime(s) {
  if (s == null) return '-';
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
  return `${Math.floor(s / 86400)}d ${Math.floor((s % 86400) / 3600)}h`;
}

/** ttl in ms: negative means no expiry. */
export function fmtTtl(ms) {
  if (ms == null || ms < 0) return 'no expiry';
  const s = ms / 1000;
  if (s < 10) return `${s.toFixed(1)}s`;
  const t = Math.round(s); // round once, then split, so we never print "1m 60s"
  if (t < 60) return `${t}s`;
  if (t < 3600) return `${Math.floor(t / 60)}m ${String(t % 60).padStart(2, '0')}s`;
  return `${Math.floor(t / 3600)}h ${Math.floor((t % 3600) / 60)}m`;
}

export const num = (n) => (n == null ? '-' : new Intl.NumberFormat().format(n));

export function relTime(ms, now = Date.now()) {
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 3) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}
