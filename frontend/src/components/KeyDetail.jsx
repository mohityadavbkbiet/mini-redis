import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../context/AppContext.jsx';
import { useNow, usePolling } from '../usePolling.js';
import { CopyButton, Empty, Panel, Skeleton } from './Ui.jsx';
import TypeBadge from './TypeBadge.jsx';
import TtlBar from './TtlBar.jsx';

/** Inspect and edit one key. Every action is a real command sent to the server. */
export default function KeyDetail({ name, onClose, onChanged }) {
  const { toast } = useApp();
  const { data, error, reload } = usePolling(() => api.key(name), 1500, [name]);
  const now = useNow(1000);
  const [edit, setEdit] = useState(null);
  const [item, setItem] = useState('');
  const [field, setField] = useState('');
  const [fval, setFval] = useState('');
  const [ttl, setTtl] = useState('');
  const [confirm, setConfirm] = useState(false);

  useEffect(() => { setEdit(null); setItem(''); setField(''); setFval(''); setTtl(''); setConfirm(false); }, [name]);

  async function run(args, ok) {
    try {
      const r = await api.exec(args);
      if (r.kind === 'error') { toast(r.display, 'error'); return null; }
      if (ok) toast(ok);
      await reload();
      onChanged();
      return r;
    } catch (e) { toast(e.message, 'error'); return null; }
  }

  if (error && !data) {
    return (
      <Panel title={name} icon="key" right={<button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>}>
        <Empty icon="clock-history" title="Key not found">{error}</Empty>
      </Panel>
    );
  }
  if (!data) return <Panel title={name} icon="key"><Skeleton rows={4} /></Panel>;

  const left = data.ttlMs >= 0 ? data.ttlMs - (now - data.at) : -1;

  async function saveString() {
    const keep = left;
    const r = await run(['SET', name, edit], null);
    if (!r) return;
    if (keep > 0) await api.exec(['PEXPIRE', name, String(Math.round(keep))]); // SET clears the TTL, so put it back
    toast('Saved');
    setEdit(null);
    reload();
  }

  return (
    <Panel title={name} icon="key" className="key-panel"
           right={<><CopyButton text={name} /><button type="button" className="btn btn-ghost btn-sm" onClick={onClose} aria-label="Close"><i className="bi bi-x-lg" /></button></>}>
      <div className="key-meta">
        <TypeBadge type={data.type} />
        <span className="text-body-secondary">{data.size} {data.type === 'string' ? 'chars' : data.type === 'list' ? 'items' : 'fields'}</span>
        <span className="ms-auto"><TtlBar leftMs={left} /></span>
      </div>

      {data.type === 'string' && (
        <div className="stack-sm">
          <textarea className="form-control mono" rows="5" spellCheck="false" aria-label="Value" value={edit ?? data.value} onChange={(e) => setEdit(e.target.value)} />
          <div className="d-flex gap-2 flex-wrap">
            <button type="button" className="btn btn-accent btn-sm" disabled={edit == null || edit === data.value} onClick={saveString}><i className="bi bi-check2" /> Save</button>
            {/^-?\d+$/.test(data.value) && (
              <>
                <button type="button" className="btn btn-outline-soft btn-sm" onClick={() => run(['INCR', name])}>+1</button>
                <button type="button" className="btn btn-outline-soft btn-sm" onClick={() => run(['DECR', name])}>-1</button>
              </>
            )}
          </div>
        </div>
      )}

      {data.type === 'list' && (
        <div className="stack-sm">
          <ol className="list-items">
            {data.value.map((v, i) => <li key={i}><span className="idx">{i}</span><code className="mono">{v}</code></li>)}
          </ol>
          <div className="input-row">
            <input className="form-control form-control-sm mono" placeholder="new item" aria-label="New item" value={item} onChange={(e) => setItem(e.target.value)} />
            <button type="button" className="btn btn-outline-soft btn-sm" disabled={!item} onClick={async () => { if (await run(['LPUSH', name, item])) setItem(''); }}>LPUSH</button>
            <button type="button" className="btn btn-outline-soft btn-sm" disabled={!item} onClick={async () => { if (await run(['RPUSH', name, item])) setItem(''); }}>RPUSH</button>
          </div>
          <div className="d-flex gap-2">
            <button type="button" className="btn btn-outline-soft btn-sm" onClick={() => run(['LPOP', name], 'Popped from the left')}>LPOP</button>
            <button type="button" className="btn btn-outline-soft btn-sm" onClick={() => run(['RPOP', name], 'Popped from the right')}>RPOP</button>
          </div>
        </div>
      )}

      {data.type === 'hash' && (
        <div className="stack-sm">
          <table className="table data-table hash-table">
            <thead><tr><th>Field</th><th>Value</th><th /></tr></thead>
            <tbody>
              {data.value.map((p) => (
                <tr key={p.field}>
                  <td className="mono">{p.field}</td><td className="mono">{p.value}</td>
                  <td className="text-end"><button type="button" className="btn btn-ghost btn-sm" aria-label={`Delete ${p.field}`} onClick={() => run(['HDEL', name, p.field])}><i className="bi bi-trash" /></button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="input-row">
            <input className="form-control form-control-sm mono" placeholder="field" aria-label="Field" value={field} onChange={(e) => setField(e.target.value)} />
            <input className="form-control form-control-sm mono" placeholder="value" aria-label="Field value" value={fval} onChange={(e) => setFval(e.target.value)} />
            <button type="button" className="btn btn-outline-soft btn-sm" disabled={!field} onClick={async () => { if (await run(['HSET', name, field, fval])) { setField(''); setFval(''); } }}>HSET</button>
          </div>
        </div>
      )}

      <hr className="my-3" />
      <div className="input-row">
        <input type="number" min="1" className="form-control form-control-sm" placeholder="seconds" aria-label="Expire after seconds" value={ttl} onChange={(e) => setTtl(e.target.value)} />
        <button type="button" className="btn btn-outline-soft btn-sm" disabled={!(Number(ttl) > 0)} onClick={async () => { if (await run(['EXPIRE', name, ttl], 'Expiry set')) setTtl(''); }}>EXPIRE</button>
        <button type="button" className="btn btn-outline-soft btn-sm" disabled={left < 0} onClick={() => run(['PERSIST', name], 'Expiry removed')}>PERSIST</button>
      </div>
      <div className="mt-3">
        {!confirm
          ? <button type="button" className="btn btn-danger-soft btn-sm" onClick={() => setConfirm(true)}><i className="bi bi-trash" /> Delete key</button>
          : (
            <div className="d-flex gap-2 align-items-center">
              <span className="small">Delete <b className="mono">{name}</b>?</span>
              <button type="button" className="btn btn-danger btn-sm" onClick={async () => { if (await run(['DEL', name], 'Key deleted')) onClose(); }}>Yes, delete</button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirm(false)}>Cancel</button>
            </div>
          )}
      </div>
    </Panel>
  );
}
