import { useState } from 'react';
import { PageHeader, Panel } from '../components/Ui.jsx';

function Box({ x, y, w = 150, h = 56, title, sub, tone = '' }) {
  return (
    <g className={`abox ${tone}`}>
      <rect x={x} y={y} width={w} height={h} rx="12" />
      <text x={x + w / 2} y={y + h / 2 - (sub ? 3 : -5)} textAnchor="middle" className="abox-t">{title}</text>
      {sub && <text x={x + w / 2} y={y + h / 2 + 14} textAnchor="middle" className="abox-s">{sub}</text>}
    </g>
  );
}
function Arrow({ d, label, lx, ly }) {
  return (
    <g>
      <path d={d} className="aline" markerEnd="url(#arrowhead)" />
      {label && <text x={lx} y={ly} className="alabel" textAnchor="middle">{label}</text>}
    </g>
  );
}

const QA = [
  ['Why does INCR never lose an update?',
    'Every change goes through ConcurrentHashMap.compute(), which locks only that one key while the new value is calculated. Twenty clients incrementing the same key are serialised, and clients touching different keys never wait for each other.'],
  ['How does a key expire?',
    'Each key stores an absolute expireAt time. Reading an expired key removes it (lazy expiry), and a background thread sweeps keys nobody reads. Absolute times also make the AOF exact: SET with EX is logged as SET plus PEXPIREAT, so a replay after downtime drops keys that should be gone.'],
  ['What happens if the process crashes?',
    'Each write command is appended to the AOF and flushed to the OS immediately, so a process crash loses nothing. fsync policy decides power-loss safety: always = none lost, everysec = at most about a second. On restart the file is replayed, and a half-written last command is detected and cut off.'],
  ['Why does BGREWRITEAOF take a write lock?',
    'Normal commands hold a read lock while they run and log. The rewrite takes the write lock so no command is half-applied during the snapshot; otherwise an INCR could be in the snapshot and also logged afterwards, and be counted twice on replay. The trade-off is a short write pause.'],
  ['Why a thread per connection, not an event loop?',
    'It is simpler and fine for hundreds of clients. Real Redis uses one thread and an event loop so it needs no locks and handles very many connections. A Java NIO Selector loop is the natural next step.'],
];

export default function Architecture() {
  const [open, setOpen] = useState(0);
  return (
    <>
      <PageHeader title="Architecture" subtitle="How a command travels through the server, what the wire protocol looks like, and the questions an interviewer is likely to ask." />

      <Panel title="System overview" icon="diagram-3">
        <div className="diagram-scroll">
          <svg viewBox="0 0 940 360" className="diagram" role="img" aria-label="Architecture diagram">
            <defs>
              <marker id="arrowhead" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" className="ahead" /></marker>
            </defs>
            <Box x={10} y={60} w={140} title="redis-cli / app" sub="speaks RESP" tone="ui" />
            <Box x={10} y={230} w={140} title="This web UI" sub="React + Bootstrap" tone="ui" />
            <Box x={200} y={60} w={160} title="TCP server" sub="thread per connection" />
            <Box x={200} y={230} w={160} title="HTTP gateway" sub="JSON for the browser" />
            <Box x={410} y={145} w={150} title="Commands" sub="parse, execute, log" tone="core" />
            <Box x={620} y={60} w={170} title="Store" sub="ConcurrentHashMap" tone="core" />
            <Box x={620} y={230} w={170} title="AOF log" sub="append + fsync policy" tone="store" />
            <Box x={820} y={60} w={110} title="Sweeper" sub="every 200 ms" />
            <Box x={820} y={230} w={110} title="Disk" tone="store" />
            <Arrow d="M150 88H200" label="RESP" lx={175} ly={80} />
            <Arrow d="M150 258H200" label="HTTP" lx={175} ly={250} />
            <Arrow d="M360 88L430 145" />
            <Arrow d="M360 258L430 201" />
            <Arrow d="M560 160L640 116" label="read lock" lx={615} ly={128} />
            <Arrow d="M560 190L640 230" label="log writes" lx={612} ly={222} />
            <Arrow d="M820 88H790" />
            <Arrow d="M790 258H820" />
          </svg>
        </div>
      </Panel>

      <div className="grid-1-1">
        <Panel title="What goes over the wire (RESP)" icon="code-slash">
          <p className="small text-body-secondary">Typing <code className="mono">SET name mohit</code> in redis-cli sends exactly these bytes. Each word is a length-prefixed bulk string, so values can contain spaces or even line breaks.</p>
          <pre className="terminal small">{`*3\\r\\n          array of 3 items
$3\\r\\nSET\\r\\n      bulk string, 3 bytes
$4\\r\\nname\\r\\n     bulk string, 4 bytes
$5\\r\\nmohit\\r\\n    bulk string, 5 bytes`}</pre>
          <p className="small text-body-secondary mt-3 mb-1">The server answers with a one-line reply:</p>
          <pre className="terminal small">{`+OK\\r\\n            simple string
:42\\r\\n            integer
$5\\r\\nmohit\\r\\n   bulk string  ($-1 = nil)
-ERR ...\\r\\n       error`}</pre>
        </Panel>
        <Panel title="Life of a command" icon="signpost-split">
          <ol className="steps">
            <li>The <b>connection thread</b> reads bytes and parses one RESP array (or an inline command typed in telnet).</li>
            <li><b>Commands</b> takes the read lock, checks the argument count and runs the operation on the <b>Store</b>.</li>
            <li>The key is changed atomically inside <code className="mono">compute()</code>; expired keys count as missing.</li>
            <li>A write is appended to the <b>AOF</b> (TTLs as absolute times) before the reply is sent.</li>
            <li>The reply is encoded; if more pipelined commands are waiting, they are answered first and flushed together.</li>
          </ol>
        </Panel>
      </div>

      <h2 className="section-title">Interview talking points</h2>
      <div className="qa">
        {QA.map(([q, a], i) => (
          <div key={q} className={`qa-item${open === i ? ' open' : ''}`}>
            <button type="button" aria-expanded={open === i} onClick={() => setOpen(open === i ? -1 : i)}><span>{q}</span><i className="bi bi-chevron-down" /></button>
            {open === i && <p>{a}</p>}
          </div>
        ))}
      </div>
    </>
  );
}
