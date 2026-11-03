// Cloudflare Pages runs this before serving website/. It sends the other hosts that
// serve the site to kyllene.app, so search engines see one address. Deploy previews
// (<hash>.kyllene.pages.dev) are left alone.
const CANONICAL = "kyllene.app";
const REDIRECTED = new Set(["www.kyllene.app", "kyllene.pages.dev"]);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (REDIRECTED.has(url.hostname)) {
      url.hostname = CANONICAL;
      url.protocol = "https:";
      return Response.redirect(url.toString(), 301);
    }
    return env.ASSETS.fetch(request);
  },
};
