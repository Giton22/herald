import keysyms from './vendor/core/input/keysymdef.js';
import KeyTable from './vendor/core/input/keysym.js';

const GUARD = '_'.repeat(16);
const SPECIAL_KEYS = {
  Enter: [KeyTable.XK_Return, 'Enter'], Tab: [KeyTable.XK_Tab, 'Tab'],
  Escape: [KeyTable.XK_Escape, 'Escape'], Delete: [KeyTable.XK_Delete, 'Delete'],
  ArrowLeft: [KeyTable.XK_Left, 'ArrowLeft'], ArrowRight: [KeyTable.XK_Right, 'ArrowRight'],
  ArrowUp: [KeyTable.XK_Up, 'ArrowUp'], ArrowDown: [KeyTable.XK_Down, 'ArrowDown'],
};

export class TextKeyboard {
  constructor(getSession) { this.getSession = getSession; this.reset(); }
  reset() { this.value = GUARD; return this.value; }
  special(key) {
    const session = this.getSession(), mapping = SPECIAL_KEYS[key];
    if (!session || !mapping) return false;
    session.display.sendKey(...mapping);
    return true;
  }
  accept(value, composing = false) {
    if (!this.getSession()) return this.reset();
    const before = [...this.value], after = [...value];
    let common = 0;
    while (common < before.length && common < after.length && before[common] === after[common]) common++;
    for (let index = common; index < before.length; index++) {
      const session = this.getSession();
      if (!session) return this.reset();
      session.display.sendKey(KeyTable.XK_BackSpace, 'Backspace');
    }
    for (const character of after.slice(common)) {
      const session = this.getSession();
      if (!session) return this.reset();
      if (character === '\n') session.display.sendKey(KeyTable.XK_Return, 'Enter');
      else if (character === '\t') session.display.sendKey(KeyTable.XK_Tab, 'Tab');
      else session.display.sendKey(keysyms.lookup(character.codePointAt(0)));
    }
    this.value = !composing && (after.length < 4 || after.length > 1000) ? GUARD : value;
    return this.value;
  }
}

function sameSession(first, second) {
  return !!first && !!second && first.display === second.display &&
    first.generation === second.generation && first.epoch === second.epoch;
}

export class ClipboardBridge {
  constructor(getSession, clipboard, { ClipboardItemType = globalThis.ClipboardItem, timeoutMs = 4000, readSelection = null } = {}) {
    this.getSession = getSession; this.clipboard = clipboard;
    this.ClipboardItemType = ClipboardItemType; this.timeoutMs = timeoutMs;
    this.readSelection = readSelection;
    this.operation = null;
  }
  get busy() { return !!this.operation; }
  reset() {
    const operation = this.operation;
    this.operation = null;
    if (operation) {
      clearTimeout(operation.timer);
      operation.reject?.(Error('Control changed. Clipboard operation cancelled.'));
    }
  }
  begin() {
    const session = this.getSession();
    if (!session) throw Error('Take control to use the clipboard.');
    if (this.busy) throw Error('A clipboard operation is already in progress.');
    const operation = { session };
    this.operation = operation;
    return operation;
  }
  assertActive(operation) {
    if (this.operation !== operation || !sameSession(operation.session, this.getSession())) {
      throw Error('Control changed. Clipboard operation cancelled.');
    }
  }
  finish(operation) {
    clearTimeout(operation.timer);
    if (this.operation === operation) this.operation = null;
  }
  shortcut(operation, keysym, code) {
    this.assertActive(operation);
    const display = operation.session.display;
    display.sendKey(KeyTable.XK_Control_L, 'ControlLeft', true);
    try { display.sendKey(keysym, code); }
    finally { display.sendKey(KeyTable.XK_Control_L, 'ControlLeft', false); }
  }

  async copy() {
    const operation = this.begin();
    let rejectText;
    try {
      if (!this.clipboard?.writeText && !(this.clipboard?.write && this.ClipboardItemType)) {
        throw Error('Phone clipboard access is unavailable in this browser.');
      }
      if (typeof this.readSelection !== 'function') throw Error('Protected Copy is unavailable on this connection.');
      const text = new Promise((resolve, reject) => {
        operation.resolve = resolve; operation.reject = reject; rejectText = reject;
      });
      text.catch(() => {});
      // Start the permission-sensitive browser write in the original tap handler.
      let write;
      if (this.clipboard.write && this.ClipboardItemType) {
        const data = text.then(value => {
          this.assertActive(operation);
          return new Blob([value], { type: 'text/plain' });
        });
        data.catch(() => {});
        write = this.clipboard.write([new this.ClipboardItemType({ 'text/plain': data })]);
      } else {
        write = text.then(value => {
          this.assertActive(operation);
          return this.clipboard.writeText(value);
        });
      }
      // The authenticated RPC reads PRIMARY under the existing human lease.
      // No VNC clipboard export, copy shortcut or desktop clipboard mutation.
      Promise.resolve().then(async () => {
        this.assertActive(operation);
        operation.timer = setTimeout(() => operation.reject(Error('Selected-text read timed out.')), this.timeoutMs);
        const value = await this.readSelection(operation.session);
        this.assertActive(operation);
        if (typeof value !== 'string' || !value) throw Error('No text selection is available in this app.');
        if (new TextEncoder().encode(value).byteLength > 65536) throw Error('Selected text is too large for this viewer.');
        operation.resolve(value);
      }).catch(operation.reject);
      await write;
    } finally {
      rejectText?.(Error('Clipboard operation ended.'));
      this.finish(operation);
    }
  }
  async paste() {
    const operation = this.begin();
    try {
      if (!this.clipboard?.readText) throw Error('Phone clipboard access is unavailable in this browser.');
      const text = await this.clipboard.readText();
      this.assertActive(operation);
      if (text.length > 65536) throw Error('Clipboard text is too large for this viewer.');
      await operation.session.display.clipboardPasteAndWait(text);
      this.assertActive(operation);
      // The complete ClientCutText payload precedes Ctrl+V on the ordered
      // RFB transport, without waiting for a lazy extended Request.
      this.shortcut(operation, 0x76, 'KeyV');
    } finally { this.finish(operation); }
  }
}
