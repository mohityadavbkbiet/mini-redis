import { useCallback, useEffect, useRef, useState } from 'react';

/** Calls fn immediately and then every `ms` milliseconds. Pass key values in `deps` to restart on change. */
export function usePolling(fn, ms = 2000, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const load = useCallback(async () => {
    try {
      setData(await fnRef.current());
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    setData(null);
    load();
    const t = setInterval(load, ms);
    return () => clearInterval(t);
  }, [ms, load, ...deps]);

  return { data, error, reload: load };
}

/** Re-renders every `ms` milliseconds and returns Date.now(); used for live TTL countdowns. */
export function useNow(ms = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return now;
}
