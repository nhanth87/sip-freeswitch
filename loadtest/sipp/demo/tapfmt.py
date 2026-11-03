#!/usr/bin/env python3
"""Format tshark field lines into a live 'who talks to whom' feed with node names."""
import sys, time
ROLE = {"172.20.0.1": "UE", "172.20.10.20": "P-CSCF", "172.20.10.21": "I-CSCF", "172.20.10.22": "S-CSCF",
        "172.20.10.10": "HSS", "172.20.10.30": "FreeSWITCH", "172.20.10.11": "MME"}
DIA = {300: "UAR", 301: "SAR", 302: "LIR", 303: "MAR", 316: "ULR", 318: "AIR"}
DIA_A = {300: "UAA", 301: "SAA", 302: "LIA", 303: "MAA", 316: "ULA", 318: "AIA"}
B, G, Y, C, M, D0 = "\033[1m", "\033[32m", "\033[33m", "\033[36m", "\033[35m", "\033[0m"
print(f"{B}{C} LIVE wire tap — messages crossing the services (sf-net bridge){D0}\n", flush=True)
for line in sys.stdin:
    f = line.rstrip("\n").split("\t")
    if len(f) < 12:
        continue
    t, src, dst, req, st, cseq, cmd, isreq, rc, erc, seq, pt = f[:12]
    a, b = ROLE.get(src, src), ROLE.get(dst, dst)
    ts = time.strftime("%H:%M:%S", time.localtime(float(t))) + ("%.3f" % (float(t) % 1))[1:]
    if cmd:
        c = int(cmd.split(",")[0])
        if isreq.startswith("True"):
            msg, col = f"Cx {DIA.get(c, c)}", Y
        else:
            msg, col = f"Cx {DIA_A.get(c, c)}  result {(erc or rc).split(',')[0]}", Y
        proto = "DIAMETER"
    elif req:
        msg, col, proto = req.replace(" SIP/2.0", ""), C, "SIP"
    elif st:
        msg, col, proto = st.replace("SIP/2.0 ", "") + f"  ({cseq})", G, "SIP"
    elif seq:
        msg, col, proto = f"media G.711 seq={seq}", M, "RTP"
    else:
        continue
    print(f" {ts}  {a:>10} → {b:<10} {col}{proto:<8} {msg}{D0}", flush=True)
