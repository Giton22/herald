// Only an explicit open or retry may start a stopped desktop.
export class ScreenOpenError extends Error {
  constructor(message) { super(message); this.name = 'ScreenOpenError'; }
}

const START_TIMEOUT_MS = 90000;
const STATUS_TIMEOUT_MS = 3000;
const RECONCILE_ATTEMPTS = 3;
const RECONCILE_DELAY_MS = 1000;

export async function observeScreen(client, { explicit, current, starting = () => {} }) {
  const check = () => { if (!current() || client.closed) throw Error('Stale connection'); };
  const valid = result => result && typeof result.running === 'boolean' &&
    (result.placement === 'gateway' || result.placement === 'terminal:docker' || result.placement === 'terminal:ssh');
  check();
  let state = await client.request('display.status');
  check();
  if (!valid(state)) throw new ScreenOpenError('Desktop status unavailable. Retry to check again.');
  if (!state.running) {
    if (!explicit) throw new ScreenOpenError('Desktop is stopped. Select Retry to start it.');
    starting();
    try { state = await client.request('display.start', {}, { timeoutMs: START_TIMEOUT_MS }); }
    catch (error) {
      // Rejection or timeout does not prove the server stopped starting.
      // Only status on this transport can confirm it; never start again here.
      const timedOut = error?.code === 'RPC_TIMEOUT';
      for (let i = 0; i < RECONCILE_ATTEMPTS && current() && !client.closed; i++) {
        try {
          state = await client.request('display.status', {}, { timeoutMs: STATUS_TIMEOUT_MS });
          if (!current() || client.closed) break;
          if (valid(state) && state.running) break;
        } catch { /* status is inconclusive */ }
        if (i + 1 < RECONCILE_ATTEMPTS && current() && !client.closed)
          await new Promise(resolve => setTimeout(resolve, 1000));
      }
      if (client.closed) throw new ScreenOpenError('Desktop start state unknown after gateway disconnect. Select Retry to check again.');
      if (!current()) throw Error('Stale connection');
      if (!valid(state) || !state.running) throw new ScreenOpenError(timedOut
        ? 'Desktop start timed out; its state is unknown. Select Retry to check again.'
        : 'Desktop start could not be confirmed. Its state is unknown. Select Retry to check again.');
    }
    if (!current()) throw Error('Stale connection');
    if (client.closed) throw new ScreenOpenError('Desktop start could not be confirmed. Select Retry to check again.');
    if (!valid(state) || !state.running) throw new ScreenOpenError('Desktop did not start. Select Retry to check again.');
  }
  const result = await client.request('display.observe', {});
  check();
  return result;
}

export function clipboardControls(placement, ready, busy, clipboard) {
  const sandbox = placement === 'terminal:docker' || placement === 'terminal:ssh';
  return {
    sandbox,
    copyDisabled: sandbox || !ready || busy || !(clipboard?.writeText || (clipboard?.write && globalThis.ClipboardItem)),
    pasteDisabled: !ready || busy || !clipboard?.readText,
  };
}
