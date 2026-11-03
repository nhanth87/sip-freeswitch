#!/usr/bin/env python3
"""Wireshark scene: the demo pcap in five views (filter + custom columns), each recorded off-screen."""
import json, os, subprocess, time

D = os.path.dirname(os.path.abspath(__file__))
WS = os.path.join(os.path.dirname(D), "wsconf")
R = "/home/meodien/Desktop/ethiopia-working-dir/worktrees/voice-service/sip-freeswitch"
PCAP = R + "/loadtest/pcaps/volte/volte-demo-live.pcap"
OUT = D + "/out"

BASE = [("No.", "%m", 55), ("Time", "%t", 85), ("Source", "%s", 115), ("Destination", "%d", 115), ("Protocol", "%p", 85)]
VIEWS = [
    ("ws1-signalling", "sip || (diameter && diameter.cmd.code != 280)", BASE + [
        ("Cx", "%Cus:diameter.cmd.code", 45),
        ("Result", "%Cus:diameter.Result-Code or diameter.Experimental-Result-Code", 70),
        ("Public-Identity / User", "%Cus:diameter.Public-Identity or diameter.User-Name", 260),
        ("Server-Name", "%Cus:diameter.Server-Name", 140),
        ("Info", "%i", 560)], 12),
    ("ws2-aka-vector", "diameter.cmd.code == 303", BASE + [
        ("User-Name", "%Cus:diameter.User-Name", 130),
        ("Scheme", "%Cus:diameter.3GPP-SIP-Authentication-Scheme", 130),
        ("SIP-Authenticate = RAND || AUTN", "%Cus:diameter.3GPP-SIP-Authenticate", 520),
        ("SIP-Authorization = XRES", "%Cus:diameter.3GPP-SIP-Authorization", 260),
        ("CK", "%Cus:diameter.Confidentiality-Key", 270),
        ("Result", "%Cus:diameter.Result-Code", 130)], 10),
    ("ws3-aka-register", "sip.auth.nonce || sip.auth.digest.response", BASE + [
        ("Info", "%i", 260),
        ("algorithm", "%Cus:sip.auth.algorithm", 100),
        ("nonce = base64(RAND || AUTN)", "%Cus:sip.auth.nonce", 420),
        ("AKA response", "%Cus:sip.auth.digest.response", 290)], 10),
    ("ws4-invite-sdp", 'sip.Method == "INVITE" || (sip.Status-Code == 200 && sip.CSeq.method == "INVITE")', BASE + [
        ("Info", "%i", 340),
        ("SDP c= address", "%Cus:sdp.connection_info.address", 150),
        ("SDP m= port", "%Cus:sdp.media.port", 100),
        ("codec", "%Cus:sdp.media.format", 260)], 10),
    ("ws5-rtp", "rtp", BASE + [
        ("SSRC", "%Cus:rtp.ssrc", 110), ("Seq", "%Cus:rtp.seq", 70), ("Timestamp", "%Cus:rtp.timestamp", 110),
        ("PT", "%Cus:rtp.p_type", 140), ("Info", "%i", 520)], 10),
]

def clients():
    return json.loads(subprocess.run(["hyprctl", "clients", "-j"], capture_output=True, text=True).stdout)

def close_ws3():
    for c in clients():
        if c["workspace"]["id"] == 3:
            subprocess.run(["hyprctl", "eval", f"hl.dispatch(hl.dsp.window.close({{ window = 'address:{c['address']}' }}))"],
                           capture_output=True)

def write_conf(cols):
    fmt = ",".join(f'"{t}","{f}"' for t, f, _ in cols)
    prefs = open(WS + "/preferences").read().splitlines()
    prefs = [l for l in prefs if not l.startswith("gui.column.format")] + ["gui.column.format: " + fmt]
    open(WS + "/preferences", "w").write("\n".join(prefs) + "\n")
    lines = ["# Column widths.", "column.width:"]
    for _, f, w in cols:
        key = f'"{f}"' if f.startswith("%Cus") else f
        lines.append(f"        {key}, {w},")
    lines[-1] = lines[-1].rstrip(",")
    open(WS + "/recent", "w").write("\n".join(lines) + "\n")

env = dict(os.environ, WIRESHARK_CONFIG_DIR=WS)
ONLY = os.environ.get("ONLY")
for name, flt, cols, secs in VIEWS:
    if ONLY and name != ONLY:
        continue
    close_ws3()
    time.sleep(1.5)
    write_conf(cols)
    subprocess.Popen(["wireshark", "-r", PCAP, "-Y", flt], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(40):
        if any("ireshark" in c["class"] and c["workspace"]["id"] == 3 for c in clients()):
            break
        time.sleep(0.5)
    time.sleep(4)  # packet list rendered
    rec = subprocess.Popen(["wf-recorder", "-o", "VOLTE-DEMO", "-r", "30", "-f", f"{OUT}/{name}.mp4", "-y"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(secs)
    rec.send_signal(2)
    rec.wait()
    print("recorded", name, flush=True)
close_ws3()
