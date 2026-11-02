// noVNC teardown, including protocol failures, requests normal handback.
// A socket already closing cannot have its close frame changed.
export function displayChannel(socket) {
  return {
    get readyState() { return socket.readyState; },
    get protocol() { return socket.protocol; },
    get binaryType() { return socket.binaryType; },
    set binaryType(value) { socket.binaryType = value; },
    get onopen() { return socket.onopen; }, set onopen(value) { socket.onopen = value; },
    get onmessage() { return socket.onmessage; }, set onmessage(value) { socket.onmessage = value; },
    get onerror() { return socket.onerror; }, set onerror(value) { socket.onerror = value; },
    get onclose() { return socket.onclose; }, set onclose(value) { socket.onclose = value; },
    send(data) { socket.send(data); },
    close() { if (socket.readyState < 2) socket.close(1000, 'Viewer disconnected'); },
  };
}
