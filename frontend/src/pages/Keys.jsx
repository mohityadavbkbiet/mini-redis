import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api.js';
import { useNow, usePolling } from '../usePolling.js';
import { TYPES, TYPE_ICON } from '../format.js';
import { Empty, PageHeader, Panel, Skeleton } from '../components/Ui.jsx';
import TypeBadge from '../components/TypeBadge.jsx';
import TtlBar from '../components/TtlBar.jsx';
import KeyDetail from '../components/KeyDetail.jsx';
import NewKey from '../components/NewKey.jsx';

export default function Keys() {
  const [params, setParams] = useSearchParams();
  const selected = params.get('k') || '';
  const type = params.get('type') || '';
  const pattern = params.get('q') || '*';
  const [creating, setCreating] = useState(false);
  const [text, setText] = useState(params.get('q') || '');
  const now = useNow(1000);

  // Always edit the latest URL params (a stale copy would bring back a key we just closed).
  const paramsRef = useRef(params);
  paramsRef.current = params;
  const setParamsRef = useRef(setParams);
  setParamsRef.current = setParams;
  const update = useCallback((mutate) => {
    const n = new URLSearchParams(paramsRef.current);
    mutate(n);
    setParamsRef.current(n, { replace: true });
  }, []);

  // wait a moment after typing before searching
  useEffect(() => {
    const t = setTimeout(() => {
      if ((paramsRef.current.get('q') || '') === (text === '*' ? '' : text)) return;
      update((n) => { if (text && text !== '*') n.set('q', text); else n.delete('q'); });
    }, 250);
    return () => clearTimeout(t);
  }, [text, update]);

  const { data, error, reload } = usePolling(() => api.keys(pattern), 2000, [pattern]);

  function setParam(k, v) {
    update((n) => { if (v) n.set(k, v); else n.delete(k); });
  }

  const all = data?.keys || [];
  const counts = Object.fromEntries(TYPES.map((t) => [t, all.filter((k) => k.type === t).length]));
  const rows = type ? all.filter((k) => k.type === type) : all;

  return (
    <>
      <PageHeader title="Keys" subtitle="Browse, search and edit what is stored. Patterns use glob syntax: user:*, *:1, user:[12].">
        <button type="button" className="btn btn-accent" onClick={() => { setCreating(true); setParam('k', ''); }}><i className="bi bi-plus-lg" /> New key</button>
      </PageHeader>

      <div className="grid-3-2 keys-grid">
        <Panel flush>
          <div className="toolbar">
            <div className="search">
              <i className="bi bi-search" />
              <input className="form-control mono" placeholder="Pattern, e.g. user:*" value={text} onChange={(e) => setText(e.target.value)} aria-label="Key pattern" />
            </div>
            <div className="chips">
              <button type="button" className={`chip${!type ? ' on' : ''}`} onClick={() => setParam('type', '')}>All <b>{all.length}</b></button>
              {TYPES.map((t) => (
                <button key={t} type="button" className={`chip${type === t ? ' on' : ''}`} onClick={() => setParam('type', t)}>
                  <i className={`bi bi-${TYPE_ICON[t]}`} />{t} <b>{counts[t]}</b>
                </button>
              ))}
            </div>
          </div>

          {error && !data ? <Empty icon="plug" title="Cannot load keys">{error}</Empty>
            : !data ? <div className="panel-body"><Skeleton rows={6} /></div>
            : rows.length === 0 ? <Empty icon="funnel" title="No keys here">{all.length ? 'Nothing matches this type filter.' : 'Create one with New key, or use the Scenarios page to generate sample data.'}</Empty>
            : (
              <div className="table-responsive">
                <table className="table data-table align-middle mb-0">
                  <thead><tr><th>Key</th><th>Type</th><th className="text-end">Size</th><th>TTL</th></tr></thead>
                  <tbody>
                    {rows.map((k) => {
                      const left = k.ttlMs >= 0 ? k.ttlMs - (now - data.at) : -1;
                      return (
                        <tr key={k.key} className={`clickable${selected === k.key ? ' selected' : ''}`}
                            onClick={() => { setCreating(false); setParam('k', k.key); }}>
                          <td className="mono id">{k.key}</td>
                          <td><TypeBadge type={k.type} /></td>
                          <td className="text-end mono">{k.size}</td>
                          <td>{k.ttlMs >= 0 && left <= 0 ? <span className="text-body-secondary">expired</span> : <TtlBar leftMs={left} />}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          {data && data.total > data.shown && <div className="pager"><span className="small text-body-secondary">Showing {data.shown} of {data.total}. Narrow the pattern to see more.</span></div>}
        </Panel>

        <div className="stack side-col">
          {creating ? (
            <NewKey onCancel={() => setCreating(false)} onCreated={(name) => { setCreating(false); setParam('k', name); reload(); }} />
          ) : selected ? (
            <KeyDetail name={selected} onClose={() => setParam('k', '')} onChanged={reload} />
          ) : (
            <Panel><Empty icon="hand-index" title="Select a key">Click a row to see its value, change it, set an expiry or delete it.</Empty></Panel>
          )}
        </div>
      </div>
    </>
  );
}
