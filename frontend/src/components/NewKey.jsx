import { useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../context/AppContext.jsx';
import { Panel } from './Ui.jsx';

/** Creates a string, list or hash key (plus optional TTL) by sending the matching commands. */
export default function NewKey({ onCreated, onCancel }) {
  const { toast } = useApp();
  const [name, setName] = useState('');
  const [type, setType] = useState('string');
  const [value, setValue] = useState('');
  const [ttl, setTtl] = useState('');
  const [busy, setBusy] = useState(false);

  const hint = { string: 'The value', list: 'One item per line (pushed to the right end)', hash: 'One field=value per line' }[type];

  async function submit(e) {
    e.preventDefault();
    if (!name.trim()) { toast('Key name is required', 'error'); return; }
    const lines = value.split('\n').map((l) => l.trim()).filter(Boolean);
    let args;
    if (type === 'string') args = ['SET', name, value];
    else if (type === 'list') args = ['RPUSH', name, ...lines];
    else {
      const flat = lines.flatMap((l) => { const i = l.indexOf('='); return i > 0 ? [l.slice(0, i), l.slice(i + 1)] : []; });
      args = ['HSET', name, ...flat];
    }
    if ((type !== 'string' && args.length < 3) || (type === 'hash' && args.length < 4)) { toast('Add at least one item', 'error'); return; }
    setBusy(true);
    try {
      const r = await api.exec(args);
      if (r.kind === 'error') { toast(r.display, 'error'); setBusy(false); return; }
      if (ttl && Number(ttl) > 0) await api.exec(['EXPIRE', name, String(Number(ttl))]);
      toast(`Created ${name}`);
      onCreated(name);
    } catch (err) { toast(err.message, 'error'); setBusy(false); }
  }

  return (
    <Panel title="New key" icon="plus-circle" right={<button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>Cancel</button>}>
      <form onSubmit={submit} className="stack-sm">
        <div>
          <label className="form-label" htmlFor="nk-name">Key name</label>
          <input id="nk-name" className="form-control mono" value={name} onChange={(e) => setName(e.target.value)} placeholder="user:42" autoFocus />
        </div>
        <div className="seg" role="radiogroup" aria-label="Type">
          {['string', 'list', 'hash'].map((t) => (
            <button type="button" key={t} role="radio" aria-checked={type === t} className={type === t ? 'on' : ''} onClick={() => setType(t)}>{t}</button>
          ))}
        </div>
        <div>
          <label className="form-label" htmlFor="nk-val">{hint}</label>
          <textarea id="nk-val" rows="4" spellCheck="false" className="form-control mono" value={value} onChange={(e) => setValue(e.target.value)}
                    placeholder={type === 'hash' ? 'name=Mohit\ncity=Delhi' : type === 'list' ? 'first\nsecond' : 'hello'} />
        </div>
        <div>
          <label className="form-label" htmlFor="nk-ttl">Expire after (seconds, optional)</label>
          <input id="nk-ttl" type="number" min="1" className="form-control" value={ttl} onChange={(e) => setTtl(e.target.value)} />
        </div>
        <button className="btn btn-accent" disabled={busy}><i className="bi bi-check2" /> Create key</button>
      </form>
    </Panel>
  );
}
