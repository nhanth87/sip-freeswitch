import sys, html
rows = [l.rstrip('\n').split('|') for l in open(sys.argv[1]) if l.strip()]
out = sys.argv[2]
nodes = [('172.20.0.1', 'UE (SIPp)', 'IMS AKA UE · G.711'), ('172.20.10.20', 'P-CSCF', 'Elisa'),
         ('172.20.10.21', 'I-CSCF', 'Elisa'), ('172.20.10.22', 'S-CSCF', 'Elisa'),
         ('172.20.10.10', 'HSS / AuC', 'epc-hss · Postgres'), ('172.20.10.30', 'FreeSWITCH', 'MRF sip:1000')]
col = {ip: i for i, (ip, _, _) in enumerate(nodes)}
DIA = {300: 'UAR', 301: 'SAR', 302: 'LIR', 303: 'MAR'}
DIA_A = {300: 'UAA', 301: 'SAA', 302: 'LIA', 303: 'MAA'}
items = []
for t, src, dst, req, st, cseq, cmd, isreq, rc, erc in rows:
    if src not in col or dst not in col:
        continue
    if cmd:
        c = int(cmd)
        if isreq == 'True':
            label, kind = 'Cx ' + DIA.get(c, str(c)), 'dia'
        else:
            code = erc or rc
            label, kind = 'Cx %s %s' % (DIA_A.get(c, str(c)), code), 'dia'
    elif req:
        label, kind = req.split(' ')[0] + ' ' + req.split(' ')[1].split(';')[0], 'req'
    else:
        label, kind = st.replace('SIP/2.0 ', '') + ' (' + cseq + ')', 'rsp'
    items.append((float(t), col[src], col[dst], label, kind))
# drop SIP retransmissions (same hop + same label within 1.2 s)
seen, clean = {}, []
for it in items:
    k = (it[1], it[2], it[3])
    if k in seen and it[0] - seen[k] < 1.2:
        continue
    seen[k] = it[0]
    clean.append(it)
t0 = clean[0][0]
W, gap, top, rowh = 1180, 1180 / len(nodes), 96, 26
H = top + rowh * (len(clean) + 1) + 20
svg = ['<svg viewBox="0 0 %d %d" width="100%%" role="img" aria-label="VoLTE call flow">' % (W, H)]
for i, (ip, name, sub) in enumerate(nodes):
    x = gap * i + gap / 2
    svg.append('<text x="%.0f" y="30" class="n">%s</text><text x="%.0f" y="50" class="s">%s</text>'
               '<text x="%.0f" y="68" class="ip">%s</text>' % (x, name, x, sub, x, ip))
    svg.append('<line x1="%.0f" y1="80" x2="%.0f" y2="%d" class="life"/>' % (x, x, H - 10))
for r, (t, a, b, label, kind) in enumerate(clean):
    y = top + rowh * r
    xa, xb = gap * a + gap / 2, gap * b + gap / 2
    d = 1 if xb > xa else -1
    svg.append('<line x1="%.0f" y1="%d" x2="%.0f" y2="%d" class="m %s"/>' % (xa, y, xb - 7 * d, y, kind))
    svg.append('<path d="M%.0f %d l%d -5 v10 z" class="h %s"/>' % (xb, y, -9 * d, kind))
    svg.append('<text x="%.0f" y="%d" class="l %s">%s</text>' % ((xa + xb) / 2, y - 6, kind, html.escape(label)))
    svg.append('<text x="6" y="%d" class="t">+%.2fs</text>' % (y + 4, t - t0))
svg.append('</svg>')
page = '''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VoLTE core call flow</title><style>
:root{--bg:#0f1218;--fg:#e6e9ef;--mut:#8b93a3;--req:#6cb6ff;--rsp:#7ee2a8;--dia:#f5b759;--life:#2b3240}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
main{max-width:1240px;margin:0 auto;padding:20px 16px}
h1{font-size:22px;margin:0 0 4px}p{color:var(--mut);margin:0 0 14px}
.n{fill:var(--fg);font-weight:600;font-size:15px;text-anchor:middle}.s{fill:var(--mut);font-size:12px;text-anchor:middle}
.ip{fill:var(--mut);font:11px ui-monospace,monospace;text-anchor:middle}.life{stroke:var(--life);stroke-width:2}
.m{stroke-width:2}.m.req{stroke:var(--req)}.m.rsp{stroke:var(--rsp)}.m.dia{stroke:var(--dia);stroke-dasharray:5 3}
.h.req{fill:var(--req)}.h.rsp{fill:var(--rsp)}.h.dia{fill:var(--dia)}
.l{font:11.5px ui-monospace,monospace;text-anchor:middle}.l.req{fill:var(--req)}.l.rsp{fill:var(--rsp)}.l.dia{fill:var(--dia)}
.t{fill:var(--mut);font:10.5px ui-monospace,monospace}
.media{display:flex;gap:16px;flex-wrap:wrap;margin-top:14px}.card{border:1px solid var(--life);border-radius:8px;padding:10px 14px}
.k{color:var(--mut);font-size:12px}.v{font:600 15px ui-monospace,monospace}
.legend span{margin-right:16px;font-size:12px}
</style></head><body><main>
<h1>VoLTE core chain — one real call, every hop</h1>
<p>Captured on the lab bridge (<code>loadtest/pcaps/volte/core-call6.pcap</code>). SIP solid, Cx Diameter dashed. SIP retransmissions and 100 Trying omitted.</p>
<div class="legend"><span style="color:var(--req)">● SIP request</span><span style="color:var(--rsp)">● SIP response</span><span style="color:var(--dia)">● Cx (Diameter, TS 29.229)</span></div>
''' + '\n'.join(svg) + '''
<div class="media">
<div class="card"><div class="k">RTP uplink · UE → FreeSWITCH</div><div class="v">710 pkts · G.711 µ-law · 0 lost</div><div class="k">English speech, 14.2 s</div></div>
<div class="card"><div class="k">RTP downlink · FreeSWITCH → UE</div><div class="v">711 pkts · G.711 µ-law · 0 lost</div><div class="k">MRF tone 350+440 Hz, 14.2 s</div></div>
<div class="card"><div class="k">AuC</div><div class="v">MILENAGE · TS 33.102 Annex C SQN</div><div class="k">vector over Cx MAR, verified by the UE (SIPp USIM)</div></div>
</div></main></body></html>'''
open(out, 'w').write(page)
print(len(clean), 'messages')
