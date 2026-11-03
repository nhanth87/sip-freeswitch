#!/usr/bin/env python3
"""Live 'services running' panel: every node of the VoLTE core with state, health, CPU/MEM, and the
Diameter links really ESTABLISHED on epc-hss (read from its /proc/net/tcp)."""
import json, subprocess, time

NODES = [("UE",          None,                              "172.20.0.1",   "SIPp on the host"),
         ("P-CSCF",      "sip-freeswitch-pcscf-1",          "172.20.10.20", "Elisa"),
         ("I-CSCF",      "sip-freeswitch-icscf-1",          "172.20.10.21", "Elisa"),
         ("S-CSCF",      "sip-freeswitch-scscf-1",          "172.20.10.22", "Elisa"),
         ("HSS / AuC",   "sip-freeswitch-epc-hss-1",        "172.20.10.10", "epc-hss, MILENAGE"),
         ("Postgres",    "sip-freeswitch-postgres-epc-1",   "",             "HSS store"),
         ("FreeSWITCH",  "sip-freeswitch-freeswitch-1",     "172.20.10.30", "MRF sip:1000"),
         ("MME",         "sip-freeswitch-mme-1",            "172.20.10.11", "open5gs (S6a)")]
PEERS = {"172.20.10.11": "MME (S6a)", "172.20.10.21": "I-CSCF (Cx)", "172.20.10.22": "S-CSCF (Cx)"}
B, G, R, Y, C, D0 = "\033[1m", "\033[32m", "\033[31m", "\033[33m", "\033[36m", "\033[0m"

def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout

def links():
    out = sh("docker", "exec", "sip-freeswitch-epc-hss-1", "cat", "/proc/net/tcp")
    up = set()
    for line in out.splitlines()[1:]:
        f = line.split()
        rem, st = f[2], f[3]
        ip_hex, port_hex = rem.split(":")
        if st == "01" and int(port_hex, 16) == 3868:
            ip = ".".join(str(int(ip_hex[i:i + 2], 16)) for i in (6, 4, 2, 0))
            up.add(ip)
    return up

while True:
    names = [n[1] for n in NODES if n[1]]
    info = {c["Name"].lstrip("/"): c for c in json.loads(sh("docker", "inspect", *names) or "[]")}
    stats = {}
    for l in sh("docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}", *names).splitlines():
        n, cpu, mem = l.split("|")
        stats[n] = (cpu, mem.split("/")[0].strip())
    up = links()
    print("\033[H\033[2J", end="")
    print(f"{B}{C} VoLTE core — services running{D0}   {time.strftime('%H:%M:%S')}\n")
    print(f"{B} {'ROLE':<11}{'CONTAINER':<31}{'IP':<14}{'STATE':<17}{'CPU':>7}  {'MEM':<10}{D0}")
    for role, name, ip, what in NODES:
        if not name:
            print(f" {role:<11}{'(host) ' + what:<31}{ip:<14}{G}{'ready':<17}{D0}")
            continue
        c = info.get(name, {})
        st = c.get("State", {})
        s = st.get("Status", "missing")
        h = st.get("Health", {}).get("Status")
        label = s + (f" ({h})" if h else "")
        col = G if s == "running" and h in (None, "healthy") else (Y if s == "running" else R)
        cpu, mem = stats.get(name, ("", ""))
        print(f" {role:<11}{name:<31}{ip:<14}{col}{label:<17}{D0}{cpu:>7}  {mem:<10}")
    print(f"\n{B} Diameter links on epc-hss (TCP 3868, ESTABLISHED){D0}")
    for ip, label in PEERS.items():
        ok = ip in up
        print(f"   HSS ⇄ {label:<14} {G + 'UP' if ok else R + 'DOWN'}{D0}")
    time.sleep(3)
