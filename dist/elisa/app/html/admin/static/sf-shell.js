/* Elisa admin shell — Alpine.store('sf') and X-SF-Admin-Key forwarding. */
(function () {
  const THEME_KEY = 'sf-theme';
  const key = () => new URLSearchParams(location.search).get('key') || '';
  const headers = (extra) => Object.assign({}, extra || {}, key() ? {'X-SF-Admin-Key': key()} : {});
  document.addEventListener('htmx:configRequest', e => { if (key()) e.detail.headers['X-SF-Admin-Key'] = key(); });
  const theme = t => { document.documentElement.dataset.theme = t; localStorage.setItem(THEME_KEY, t); };
  const api = { toggleTheme: () => theme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light'),
    async postJson(path, body) { const r = await fetch(path, {method:'POST', credentials:'same-origin', headers:headers({'Content-Type':'application/json'}), body:body || ''}); return {ok:r.ok, raw:await r.text()}; } };
  document.addEventListener('alpine:init', () => Alpine.store('sf', api));
  try { theme(localStorage.getItem(THEME_KEY) || 'dark'); } catch (_) {}
})();
