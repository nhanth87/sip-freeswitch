#!/usr/bin/env python3
"""Per-leg latency of Create Session from a capture (docs/agents/epc-sgw-pgw-wire.md).

    python3 -I loadtest/gtp/attach-latency.py loadtest/pcaps/epc/epc-attach-warm.pcap

Legs (request -> its answer, matched on GTP sequence / Diameter Session-Id / PFCP sequence):
  S11   MME -> SGW-C Create Session Request .. Response
  S5-C  SGW-C -> PGW-C Create Session Request .. Response
  Gx    PGW-C -> PCRF CCR-I .. CCA
  Sxb   PGW-C -> UP  Session Establishment Request .. Response
  Sxa   SGW-C -> UP  Session Establishment Request .. Response
"""
import subprocess
import sys

SGWC, PGWC = "172.20.10.14", "172.20.10.13"


def rows(pcap, display, fields):
    out = subprocess.run(["tshark", "-r", pcap, "-Y", display, "-T", "fields", "-E", "separator=|"]
                         + sum([["-e", f] for f in fields], []), capture_output=True, text=True).stdout
    return [line.split("|") for line in out.splitlines() if line]


def pairs(reqs, rsps):
    first_req, first_rsp = {}, {}
    for t, key in reqs:
        first_req.setdefault(key, float(t))
    for t, key in rsps:
        first_rsp.setdefault(key, float(t))
    return [1000 * (first_rsp[k] - first_req[k]) for k in first_req if k in first_rsp]


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else float("nan")


def main(pcap):
    legs = {}
    g = rows(pcap, "gtpv2.message_type==32 || gtpv2.message_type==33",
             ["frame.time_epoch", "ip.src", "ip.dst", "gtpv2.message_type", "gtpv2.seq"])
    for name, dst in (("S11", SGWC), ("S5-C", PGWC)):
        reqs = [(r[0], (r[1], r[4])) for r in g if r[3] == "32" and r[2] == dst]
        rsps = [(r[0], (r[2], r[4])) for r in g if r[3] == "33" and r[1] == dst]
        legs[name] = pairs(reqs, rsps)
    d = rows(pcap, "diameter.cmd.code==272 && diameter.CC-Request-Type==1",
             ["frame.time_epoch", "diameter.flags.request", "diameter.Session-Id"])
    legs["Gx"] = pairs([(r[0], r[2]) for r in d if r[1] in ("1", "True")],
                       [(r[0], r[2]) for r in d if r[1] in ("0", "False")])
    p = rows(pcap, "pfcp.msg_type==50 || pfcp.msg_type==51",
             ["frame.time_epoch", "ip.src", "ip.dst", "pfcp.msg_type", "pfcp.seqno"])
    for name, cp in (("Sxb", PGWC), ("Sxa", SGWC)):
        reqs = [(r[0], r[4]) for r in p if r[3] == "50" and r[1] == cp]
        rsps = [(r[0], r[4]) for r in p if r[3] == "51" and r[2] == cp]
        legs[name] = pairs(reqs, rsps)
    print(f"{'leg':6} {'n':>4} {'p50 ms':>8} {'p95 ms':>8} {'max ms':>8}")
    for name, xs in legs.items():
        print(f"{name:6} {len(xs):4d} {pct(xs, 50):8.1f} {pct(xs, 95):8.1f} {max(xs) if xs else float('nan'):8.1f}")


if __name__ == "__main__":
    main(sys.argv[1])
