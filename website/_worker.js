// Cloudflare Pages runs this before serving website/. It sends the other hosts that
// serve the site to kyllene.app, so search engines see one address. Deploy previews
// (<hash>.kyllene.pages.dev) are left alone.
const CANONICAL = "kyllene.app";
const REDIRECTED = new Set(["www.kyllene.app", "kyllene.pages.dev"]);
// A software centre opens these by type: the Flatpak install file and the repository file.
const FLATPAK_TYPES = {
  ".flatpakref": "application/vnd.flatpak.ref",
  ".flatpakrepo": "application/vnd.flatpak.repo",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (REDIRECTED.has(url.hostname)) {
      url.hostname = CANONICAL;
      url.protocol = "https:";
      return Response.redirect(url.toString(), 301);
    }
    const response = await env.ASSETS.fetch(request);
    const type = Object.entries(FLATPAK_TYPES).find(([end]) => url.pathname.endsWith(end))?.[1];
    if (!type || !response.ok) return response;
    const typed = new Response(response.body, response);
    typed.headers.set("Content-Type", type);
    return typed;
  },
};
