// Dashboard fallback; Herald opens the gateway-origin HTML directly.
const host = window.__HERMES_PLUGINS__;
if (host) host.register('herald-screen', () => host.React.createElement('iframe', {
  src: '/api/plugins/herald-screen/index.html', title: 'Screen',
  style: { width: '100%', height: '100%', minHeight: '70vh', border: '0' },
}));
