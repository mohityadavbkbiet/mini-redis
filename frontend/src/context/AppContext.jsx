import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';

const Ctx = createContext(null);
export const useApp = () => useContext(Ctx);

function initialTheme() {
  try { const t = localStorage.getItem('theme'); if (t) return t; } catch { /* storage unavailable */ }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
function readNum(key, fallback) {
  try { const v = Number(localStorage.getItem(key)); return v > 0 ? v : fallback; } catch { return fallback; }
}

export function AppProvider({ children }) {
  const [theme, setTheme] = useState(initialTheme);
  const [intervalMs, setIntervalMs] = useState(() => readNum('intervalMs', 2000));
  const [paused, setPaused] = useState(false);
  const [state, setState] = useState({ info: null, error: null });
  const [samples, setSamples] = useState([]); // { t, ops, keys, clients }
  const [toasts, setToasts] = useState([]);
  const prev = useRef(null);

  useEffect(() => {
    document.documentElement.setAttribute('data-bs-theme', theme);
    try { localStorage.setItem('theme', theme); } catch { /* ignore */ }
  }, [theme]);
  useEffect(() => {
    try { localStorage.setItem('intervalMs', String(intervalMs)); } catch { /* ignore */ }
  }, [intervalMs]);

  const load = useCallback(async () => {
    try {
      const info = await api.info();
      setState({ info, error: null });
      const t = info.now;
      let ops = 0;
      if (prev.current && t > prev.current.t) ops = Math.max(0, ((info.totalCommands - prev.current.total) * 1000) / (t - prev.current.t));
      prev.current = { t, total: info.totalCommands };
      setSamples((s) => [...s.slice(-59), { t, ops: Math.round(ops), keys: info.keys, clients: info.connectedClients }]);
    } catch (e) {
      setState((s) => ({ ...s, error: e.message }));
    }
  }, []);

  useEffect(() => {
    load();
    if (paused) return undefined;
    const t = setInterval(load, intervalMs);
    return () => clearInterval(t);
  }, [load, intervalMs, paused]);

  const dismiss = useCallback((id) => setToasts((t) => t.filter((x) => x.id !== id)), []);
  const toast = useCallback((message, kind = 'success') => {
    const id = Math.random().toString(36).slice(2);
    setToasts((t) => [...t, { id, message, kind }]);
    setTimeout(() => dismiss(id), 4000);
  }, [dismiss]);

  const value = useMemo(() => ({
    theme, setTheme, intervalMs, setIntervalMs, paused, setPaused,
    ...state, samples, reload: load, toast, toasts, dismiss,
  }), [theme, intervalMs, paused, state, samples, load, toast, toasts, dismiss]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
