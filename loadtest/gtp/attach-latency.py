#!/usr/bin/env python3
"""Per-leg latency of Create Session from a capture (docs/agents/epc-sgw-pgw-wire.md).

    python3 -I loadtest/gtp/attach-latency.py loadtest/pcaps/epc/epc-attach-warm.pcap

Legs (request -> its first answer):
  S11   MME -> SGW-C Create Session Request .. Response        (GTP sequence)
  S5-C  SGW-C -> PGW-C Create Session Request .. Response      (GTP sequence)
  Gx    PGW-C -> PCRF CCR-I .. CCA                             (Session-Id)
  Sxb   PGW-C -> UP  Session Establishment Request .. Response (PFCP sequence)
  Sxa   SGW-C -> UP  Session Establishment Request .. Response (PFCP sequence)

Roles come from the flow, not from fixed addresses (docker lab or Kubernetes pods): the MME sends Create
Session Requests and never receives one; the SGW-C answers the MME; the PGW-C answers the SGW-C. A packet
seen twice (before and after a Service DNAT on a bridge capture) counts once — first copy wins.
"""
import subprocess
import sys


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
    return [1000 * (first_rsp[k] - first_req[k]) for k in first_req if k in first_rsp and first_rsp[k] >= first_req[k]]


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else float("nan")


def main(pcap):
    legs = {}
    g = rows(pcap, "gtpv2.message_type==32 || gtpv2.message_type==33",
             ["frame.time_epoch", "ip.src", "ip.dst", "gtpv2.message_type", "gtpv2.seq"])
    csr = [r for r in g if r[3] == "32"]
    rsp = [r for r in g if r[3] == "33"]
    mme = {r[1] for r in csr} - {r[2] for r in csr}
    sgwc = {r[1] for r in rsp if r[2] in mme} | {r[1] for r in csr if r[1] not in mme}
    pgwc = {r[1] for r in rsp if r[2] not in mme}
    legs["S11"] = pairs([(r[0], r[4]) for r in csr if r[1] in mme], [(r[0], r[4]) for r in rsp if r[2] in mme])
    legs["S5-C"] = pairs([(r[0], r[4]) for r in csr if r[1] not in mme],
                         [(r[0], r[4]) for r in rsp if r[2] not in mme])
    d = rows(pcap, "diameter.cmd.code==272 && diameter.CC-Request-Type==1",
             ["frame.time_epoch", "diameter.flags.request", "diameter.Session-Id"])
    legs["Gx"] = pairs([(r[0], r[2]) for r in d if r[1] in ("1", "True")],
                       [(r[0], r[2]) for r in d if r[1] in ("0", "False")])
    p = rows(pcap, "pfcp.msg_type==50 || pfcp.msg_type==51",
             ["frame.time_epoch", "ip.src", "ip.dst", "pfcp.msg_type", "pfcp.seqno"])
    for name, cp in (("Sxb", pgwc), ("Sxa", sgwc)):
        reqs = [(r[0], r[4]) for r in p if r[3] == "50" and r[1] in cp]
        rsps = [(r[0], r[4]) for r in p if r[3] == "51" and r[2] in cp]
        legs[name] = pairs(reqs, rsps)
    print(f"{'leg':6} {'n':>4} {'p50 ms':>8} {'p95 ms':>8} {'max ms':>8}")
    for name, xs in legs.items():
        print(f"{name:6} {len(xs):4d} {pct(xs, 50):8.1f} {pct(xs, 95):8.1f} {max(xs) if xs else float('nan'):8.1f}")


if __name__ == "__main__":
    main(sys.argv[1])
