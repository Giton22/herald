import test from 'node:test';
import assert from 'node:assert/strict';
import { displayChannel } from './display_channel.js';

globalThis.window = {devicePixelRatio:1, addEventListener(){}, removeEventListener(){}};
globalThis.document = {documentElement:{}, createElement:() => ({style:{}})};
Object.defineProperty(globalThis, 'navigator', {value:{platform:'Linux',userAgent:'test',maxTouchPoints:0}, configurable:true});
globalThis.MutationObserver = class { observe() {} disconnect() {} };
class Socket {
  static CONNECTING=0; static OPEN=1; static CLOSING=2; static CLOSED=3;
  constructor(holder='human', state=1) { this.readyState=state; this.holder=holder; this.closes=[]; this.protocol=''; this.binaryType=''; }
  send() {}
  close(code=1000) { if (this.readyState >= 2) return; this.closes.push(code); this.readyState=2; if (code === 1000 || code === 1001) this.holder='agent'; }
}
globalThis.WebSocket = Socket;
const {default:Websock} = await import('./vendor/core/websock.js');
const {default:RFB} = await import('./vendor/core/rfb.js');

test('bundled noVNC protocol failure tears down with normal handback', () => {
  for (const holder of ['human','agent']) {
    const socket = new Socket(holder), transport = new Websock();
    transport.attach(displayChannel(socket));
    const rfb = Object.create(RFB.prototype);
    Object.assign(rfb, {_sock:transport, _rfbConnectionState:'connected', _cursor:{detach(){}},
      _canvas:{removeEventListener(){}}, _eventHandlers:{}, _resizeObserver:{disconnect(){}},
      _keyboard:{ungrab(){}}, _gestures:{detach(){}}, _target:{removeChild(){}},
      _decoders:{}, _FBU:{encoding:123456}, dispatchEvent() {}});
    assert.equal(rfb._handleDataRect(), false);
    assert.deepEqual(socket.closes, [1000]);
    assert.equal(socket.holder, 'agent');
    assert.equal(rfb._rfbConnectionState, 'disconnected');
  }
});

test('channel handles connecting, closing, callbacks and idempotent close', () => {
  const socket = new Socket('human', 0), channel = displayChannel(socket);
  let observed; channel.onmessage = event => { observed = event.data; }; socket.onmessage({data:'frame'});
  assert.equal(observed, 'frame'); channel.binaryType='arraybuffer'; assert.equal(socket.binaryType,'arraybuffer');
  channel.close(); channel.close(); assert.deepEqual(socket.closes, [1000]);
  const closing = new Socket('human', 2); displayChannel(closing).close();
  assert.deepEqual(closing.closes, []); assert.equal(closing.holder,'human');
});
