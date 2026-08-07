/* ELISA admin shell — Alpine.store('sf') + HTMX key forwarding (OTA parity G11). */
(function () {
  const THEME_KEY = 'sf-theme';
  const RAIL_KEY = 'sf-rail-collapsed';

  function adminKeyFromUrl() {
    try {
      const v = new URLSearchParams(location.search).get('key');
      return (v && v.trim()) ? v.trim() : '';
    } catch (_) {
      return '';
    }
  }

  function authHeaders(extra) {
    const headers = extra ? Object.assign({}, extra) : {};
    const key = adminKeyFromUrl();
    if (key) headers['X-SF-Admin-Key'] = key;
    return headers;
  }

  function withKeyQuery(path) {
    const key = adminKeyFromUrl();
    if (!key) return path;
    return path + (path.includes('?') ? '&' : '?') + 'key=' + encodeURIComponent(key);
  }

  document.addEventListener('htmx:configRequest', (ev) => {
    const key = adminKeyFromUrl();
    if (key && ev.detail && ev.detail.headers) {
      ev.detail.headers['X-SF-Admin-Key'] = key;
    }
  });

  function applyTheme(theme) {
    const t = theme === 'light' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', t);
    try { localStorage.setItem(THEME_KEY, t); } catch (_) { /* ignore */ }
  }

  function toggleTheme() {
    const cur = document.documentElement.getAttribute('data-theme');
    applyTheme(cur === 'light' ? 'dark' : 'light');
  }

  function ensureHost() {
    let host = document.getElementById('sf-toast-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'sf-toast-host';
      host.setAttribute('aria-live', 'polite');
      document.body.appendChild(host);
    }
    return host;
  }

  function toast(message, kind) {
    const host = ensureHost();
    const el = document.createElement('div');
    el.className = 'sf-toast ' + (kind || 'info');
    el.textContent = message;
    host.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => {
      el.classList.remove('show');
      setTimeout(() => el.remove(), 280);
    }, kind === 'error' ? 6500 : 3200);
  }

  async function postJson(path, bodyText) {
    const url = withKeyQuery(path);
    const r = await fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: bodyText == null ? '' : bodyText
    });
    let data = null;
    const text = await r.text();
    try { data = JSON.parse(text); } catch (_) { data = { ok: r.ok, message: text }; }
    return { status: r.status, ok: r.ok && !(data && data.ok === false), data: data, raw: text };
  }

  function copyText(text, okMessage) {
    if (!text) return;
    const done = () => toast(okMessage || 'Copied', 'ok');
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => {
        fallbackCopy(text);
        done();
      });
      return;
    }
    fallbackCopy(text);
    done();
  }

  function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'absolute';
    ta.style.left = '-9999px';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (_) { /* ignore */ }
    ta.remove();
  }

  function railCollapsed() {
    try { return localStorage.getItem(RAIL_KEY) === '1'; } catch (_) { return false; }
  }

  function setRailCollapsed(v) {
    try { localStorage.setItem(RAIL_KEY, v ? '1' : '0'); } catch (_) { /* ignore */ }
    document.querySelectorAll('.sf-layout').forEach((el) => {
      el.classList.toggle('rail-collapsed', !!v);
    });
  }

  function toggleRail() {
    setRailCollapsed(!railCollapsed());
  }

  function sfShell() {
    return {
      railCollapsed: railCollapsed(),
      mobileOpen: false,
      init() {
        setRailCollapsed(this.railCollapsed);
      },
      toggleRail() {
        this.railCollapsed = !this.railCollapsed;
        setRailCollapsed(this.railCollapsed);
      },
      toggleMobile() {
        this.mobileOpen = !this.mobileOpen;
        const layout = this.$el.closest('.sf-layout') || document.querySelector('.sf-layout');
        if (layout) layout.classList.toggle('rail-open', this.mobileOpen);
      }
    };
  }

  const api = {
    toast, postJson, copyText, withKeyQuery, adminKeyFromUrl,
    applyTheme, toggleTheme, toggleRail, railCollapsed, setRailCollapsed, sfShell
  };

  document.addEventListener('alpine:init', () => {
    Alpine.store('sf', api);
    Alpine.data('sfShell', sfShell);
  });

  window.SfAdmin = api;
  try { applyTheme(localStorage.getItem(THEME_KEY) || 'dark'); } catch (_) { applyTheme('dark'); }
})();
