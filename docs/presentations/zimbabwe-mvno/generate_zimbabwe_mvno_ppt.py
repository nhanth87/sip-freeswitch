#!/usr/bin/env python3
"""Generate Zimbabwe MVNO government pitch deck (20 slides) with themed SVGs.

Theme: Zimbabwe flag palette — green, gold, red, black, white.
"""

from __future__ import annotations

import math
import subprocess
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.util import Emu, Inches, Pt

ROOT = Path(__file__).resolve().parent
SVG_DIR = ROOT / "svg"
PNG_DIR = ROOT / "png"
OUT = ROOT / "Zimbabwe_MVNO_Government_Pitch.pptx"

# Zimbabwe flag palette
GREEN = "#009B48"
GOLD = "#FCE300"
RED = "#DE2010"
BLACK = "#000000"
WHITE = "#FFFFFF"
DARK = "#0B1F14"
CREAM = "#FFF9E6"
MUTED = "#2A4A35"

RGB = {
    "green": RGBColor(0x00, 0x9B, 0x48),
    "gold": RGBColor(0xFC, 0xE3, 0x00),
    "red": RGBColor(0xDE, 0x20, 0x10),
    "black": RGBColor(0x00, 0x00, 0x00),
    "white": RGBColor(0xFF, 0xFF, 0xFF),
    "dark": RGBColor(0x0B, 0x1F, 0x14),
    "cream": RGBColor(0xFF, 0xF9, 0xE6),
    "muted": RGBColor(0x2A, 0x4A, 0x35),
}


def write_svg(name: str, content: str) -> Path:
    path = SVG_DIR / f"{name}.svg"
    path.write_text(content.strip() + "\n", encoding="utf-8")
    return path


def svg_to_png(svg: Path, png: Path, width: int = 1600) -> None:
    subprocess.run(
        ["rsvg-convert", "-w", str(width), "-o", str(png), str(svg)],
        check=True,
    )


def flag_strip(y: int = 0, h: int = 28, w: int = 1280) -> str:
    # stylized 7-band ZW-inspired strip (simplified for slides)
    bands = [GREEN, GOLD, RED, BLACK, RED, GOLD, GREEN]
    bh = h / len(bands)
    parts = []
    for i, c in enumerate(bands):
        parts.append(
            f'<rect x="0" y="{y + i * bh:.1f}" width="{w}" height="{bh + 0.5:.1f}" fill="{c}"/>'
        )
    return "\n".join(parts)


def star_points(cx: float, cy: float, r_outer: float, r_inner: float, n: int = 5) -> str:
    pts = []
    for i in range(n * 2):
        ang = -math.pi / 2 + i * math.pi / n
        r = r_outer if i % 2 == 0 else r_inner
        pts.append(f"{cx + r * math.cos(ang):.1f},{cy + r * math.sin(ang):.1f}")
    return " ".join(pts)


# ── SVG generators ──────────────────────────────────────────────────────────


def svg_cover() -> None:
    star = star_points(640, 280, 72, 30)
    write_svg(
        "01_cover",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="{DARK}"/>
      <stop offset="55%" stop-color="#0F3A22"/>
      <stop offset="100%" stop-color="{GREEN}"/>
    </linearGradient>
    <radialGradient id="glow" cx="50%" cy="40%" r="50%">
      <stop offset="0%" stop-color="{GOLD}" stop-opacity="0.35"/>
      <stop offset="100%" stop-color="{GOLD}" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="1280" height="720" fill="url(#bg)"/>
  <circle cx="640" cy="280" r="220" fill="url(#glow)"/>
  {flag_strip(0, 36)}
  <polygon points="{star}" fill="{GOLD}" stroke="{RED}" stroke-width="4"/>
  <circle cx="640" cy="280" r="18" fill="{RED}"/>
  <text x="640" y="430" text-anchor="middle" font-family="Georgia, serif" font-size="48" fill="{WHITE}" font-weight="700">
    Zimbabwe National MVNO
  </text>
  <text x="640" y="490" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="26" fill="{GOLD}">
    Sovereign Voice · 5G · Digital Identity
  </text>
  <text x="640" y="560" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="18" fill="#C8E6D0">
    Proposal to the Government of Zimbabwe
  </text>
  <text x="640" y="620" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="14" fill="#9BBFA8">
    Powered by Elisa IMS Core · 5G Core · CAMARA Silent Authentication · Profit Analytics
  </text>
  {flag_strip(684, 36)}
</svg>
""",
    )


def svg_agenda() -> None:
    items = [
        ("01", "Vision &amp; Market"),
        ("02", "MVNO Model"),
        ("03", "Elisa IMS + 5G Core"),
        ("04", "VAS &amp; CAMARA Auth"),
        ("05", "Profitability (R)"),
        ("06", "Roadmap &amp; Ask"),
    ]
    cards = []
    for i, (num, title) in enumerate(items):
        x = 80 + (i % 3) * 380
        y = 160 + (i // 3) * 220
        cards.append(
            f"""
  <rect x="{x}" y="{y}" width="340" height="180" rx="18" fill="{CREAM}" stroke="{GREEN}" stroke-width="3"/>
  <rect x="{x}" y="{y}" width="340" height="12" rx="6" fill="{GOLD}"/>
  <text x="{x + 28}" y="{y + 70}" font-family="Georgia, serif" font-size="42" fill="{RED}" font-weight="700">{num}</text>
  <text x="{x + 28}" y="{y + 120}" font-family="Helvetica, Arial, sans-serif" font-size="22" fill="{DARK}">{title}</text>
"""
        )
    write_svg(
        "02_agenda",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">Agenda</text>
  <text x="80" y="135" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">Six chapters · one national digital opportunity</text>
  {''.join(cards)}
</svg>
""",
    )


def svg_vision() -> None:
    write_svg(
        "03_vision",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{DARK}"/>
  {flag_strip(0, 24)}
  <text x="80" y="110" font-family="Georgia, serif" font-size="40" fill="{WHITE}" font-weight="700">National Vision</text>
  <text x="80" y="155" font-family="Helvetica, Arial, sans-serif" font-size="18" fill="{GOLD}">A Zimbabwe-owned MVNO that connects every citizen securely and profitably</text>
  <g transform="translate(80,210)">
    <rect width="340" height="380" rx="16" fill="#123D26" stroke="{GOLD}" stroke-width="2"/>
    <text x="28" y="60" font-size="22" fill="{GOLD}" font-family="Georgia, serif">Sovereignty</text>
    <text x="28" y="110" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">Local control of</text>
    <text x="28" y="140" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">IMS / 5G / identity</text>
    <text x="28" y="170" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">signalling &amp; data</text>
  </g>
  <g transform="translate(470,210)">
    <rect width="340" height="380" rx="16" fill="#123D26" stroke="{RED}" stroke-width="2"/>
    <text x="28" y="60" font-size="22" fill="{GOLD}" font-family="Georgia, serif">Inclusion</text>
    <text x="28" y="110" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">Affordable voice,</text>
    <text x="28" y="140" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">data &amp; digital ID</text>
    <text x="28" y="170" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">for all provinces</text>
  </g>
  <g transform="translate(860,210)">
    <rect width="340" height="380" rx="16" fill="#123D26" stroke="{GREEN}" stroke-width="2"/>
    <text x="28" y="60" font-size="22" fill="{GOLD}" font-family="Georgia, serif">Prosperity</text>
    <text x="28" y="110" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">MVNO designed</text>
    <text x="28" y="140" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">for margin — not</text>
    <text x="28" y="170" font-size="16" fill="{WHITE}" font-family="Helvetica, Arial, sans-serif">subsidy forever</text>
  </g>
</svg>
""",
    )


def svg_market() -> None:
    # simple bar chart style opportunity
    bars = [("Voice", 0.55), ("Data", 0.85), ("VAS/ID", 0.70), ("IoT", 0.40)]
    bar_svg = []
    for i, (label, h) in enumerate(bars):
        x = 120 + i * 260
        bh = int(280 * h)
        y = 520 - bh
        color = [GREEN, GOLD, RED, MUTED][i]
        bar_svg.append(
            f"""
  <rect x="{x}" y="{y}" width="160" height="{bh}" rx="8" fill="{color}"/>
  <text x="{x + 80}" y="560" text-anchor="middle" font-size="18" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">{label}</text>
  <text x="{x + 80}" y="{y - 16}" text-anchor="middle" font-size="16" fill="{MUTED}" font-family="Helvetica, Arial, sans-serif">{int(h*100)}%</text>
"""
        )
    write_svg(
        "04_market",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">Market Opportunity</text>
  <text x="80" y="140" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">Relative growth potential for a full-stack Zimbabwe MVNO (illustrative)</text>
  {''.join(bar_svg)}
  <text x="80" y="640" font-size="15" fill="{MUTED}" font-family="Helvetica, Arial, sans-serif">Data + digital identity (CAMARA) drive higher ARPU than voice alone.</text>
</svg>
""",
    )


def svg_mvno_model() -> None:
    write_svg(
        "05_mvno",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">Full-Stack MVNO Model</text>
  <text x="80" y="140" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">Not a thin reseller — we own the intelligent core</text>
  <!-- layers -->
  <rect x="80" y="180" width="1120" height="90" rx="12" fill="{GREEN}"/>
  <text x="640" y="235" text-anchor="middle" fill="{WHITE}" font-size="24" font-family="Georgia, serif">Brand · Retail · Customer Care · Billing UX</text>
  <rect x="80" y="290" width="1120" height="90" rx="12" fill="{GOLD}"/>
  <text x="640" y="345" text-anchor="middle" fill="{BLACK}" font-size="24" font-family="Georgia, serif">VAS · CAMARA Silent Auth · Analytics (R)</text>
  <rect x="80" y="400" width="1120" height="90" rx="12" fill="{RED}"/>
  <text x="640" y="455" text-anchor="middle" fill="{WHITE}" font-size="24" font-family="Georgia, serif">Elisa IMS Core · 5G Core (AMF/SMF/UPF/AUSF/UDM)</text>
  <rect x="80" y="510" width="1120" height="90" rx="12" fill="{BLACK}"/>
  <text x="640" y="565" text-anchor="middle" fill="{GOLD}" font-size="24" font-family="Georgia, serif">Host MNO Radio / Transport (wholesale agreement)</text>
  <text x="80" y="660" font-size="15" fill="{MUTED}" font-family="Helvetica, Arial, sans-serif">Margin lives in the green–gold layers: software we operate, not towers we rent.</text>
</svg>
""",
    )


def svg_architecture() -> None:
    boxes = [
        (60, 200, 200, 100, GREEN, WHITE, "UE / Apps"),
        (300, 200, 220, 100, GOLD, BLACK, "RAN / Host MNO"),
        (560, 120, 300, 80, RED, WHITE, "5G Core"),
        (560, 230, 300, 80, GREEN, WHITE, "Elisa IMS"),
        (560, 340, 300, 80, GOLD, BLACK, "CAMARA / VAS"),
        (920, 200, 280, 100, BLACK, GOLD, "Analytics R"),
    ]
    rects = []
    for x, y, w, h, fill, fg, label in boxes:
        rects.append(
            f"""
  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="{fill}" stroke="{DARK}" stroke-width="2"/>
  <text x="{x + w/2}" y="{y + h/2 + 8}" text-anchor="middle" fill="{fg}" font-size="20" font-family="Georgia, serif">{label}</text>
"""
        )
    write_svg(
        "06_architecture",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">End-to-End Architecture</text>
  <text x="80" y="140" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">Citizen → radio → intelligent core → profitable services</text>
  {''.join(rects)}
  <path d="M260 250 H300" stroke="{DARK}" stroke-width="3" marker-end="url(#m)"/>
  <path d="M520 250 H560" stroke="{DARK}" stroke-width="3"/>
  <path d="M860 250 H920" stroke="{DARK}" stroke-width="3"/>
  <defs>
    <marker id="m" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="{DARK}"/>
    </marker>
  </defs>
  <text x="80" y="520" font-size="16" fill="{MUTED}" font-family="Helvetica, Arial, sans-serif">
    Signalling &amp; session control in Elisa + 5GC · Media on MRF/FreeSWITCH · Identity via CAMARA
  </text>
</svg>
""",
    )


def svg_elisa() -> None:
    roles = [("P-CSCF", GREEN), ("I-CSCF", GOLD), ("S-CSCF", RED), ("HSS-lite", BLACK)]
    parts = []
    for i, (name, color) in enumerate(roles):
        x = 100 + i * 280
        fg = WHITE if color != GOLD else BLACK
        parts.append(
            f"""
  <rect x="{x}" y="260" width="240" height="160" rx="16" fill="{color}"/>
  <text x="{x + 120}" y="340" text-anchor="middle" fill="{fg}" font-size="26" font-family="Georgia, serif">{name}</text>
  <text x="{x + 120}" y="375" text-anchor="middle" fill="{fg}" font-size="14" font-family="Helvetica, Arial, sans-serif">Elisa IMS</text>
"""
        )
    write_svg(
        "07_elisa",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">Elisa — Lab-Grade IMS Core</text>
  <text x="80" y="145" font-family="Helvetica, Arial, sans-serif" font-size="18" fill="{MUTED}">
    Feature-flagged P/I/S-CSCF + HSS-lite · SIP via micro-jainslee · FreeSWITCH as MRF/AS
  </text>
  {''.join(parts)}
  <rect x="100" y="480" width="1080" height="120" rx="14" fill="{DARK}"/>
  <text x="140" y="530" fill="{GOLD}" font-size="18" font-family="Helvetica, Arial, sans-serif">
    Owns SIP dialog &amp; Cx · Does NOT relay RTP (not an SBC) · Target high CPS on the SIP edge
  </text>
  <text x="140" y="565" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">
    Diameter Cx · IMS AKA · Call session HA · Admin HTMX · xml_curl directory
  </text>
</svg>
""",
    )


def svg_5gc() -> None:
    nfs = [
        ("AMF", "Access &amp; Mobility"),
        ("SMF", "Session Mgmt"),
        ("UPF", "User Plane"),
        ("AUSF", "Auth Server"),
        ("UDM", "Unified Data"),
        ("NRF", "Discovery"),
    ]
    parts = []
    for i, (name, desc) in enumerate(nfs):
        col = i % 3
        row = i // 3
        x = 120 + col * 360
        y = 200 + row * 200
        fill = [GREEN, GOLD, RED, MUTED, BLACK, GREEN][i]
        fg = BLACK if fill == GOLD else WHITE
        parts.append(
            f"""
  <rect x="{x}" y="{y}" width="300" height="150" rx="16" fill="{fill}"/>
  <text x="{x + 150}" y="{y + 65}" text-anchor="middle" fill="{fg}" font-size="32" font-family="Georgia, serif">{name}</text>
  <text x="{x + 150}" y="{y + 105}" text-anchor="middle" fill="{fg}" font-size="14" font-family="Helvetica, Arial, sans-serif">{desc}</text>
"""
        )
    write_svg(
        "08_5gc",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">5G Core Network Functions</text>
  <text x="80" y="145" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">Cloud-native NFs we deploy &amp; operate for the Zimbabwe MVNO</text>
  {''.join(parts)}
</svg>
""",
    )


def svg_topology() -> None:
    write_svg(
        "09_topology",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{DARK}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{WHITE}" font-weight="700">Service Topology</text>
  <!-- UE -->
  <circle cx="160" cy="360" r="55" fill="{GOLD}"/>
  <text x="160" y="368" text-anchor="middle" font-size="18" fill="{BLACK}" font-family="Georgia, serif">UE</text>
  <!-- RAN -->
  <rect x="280" y="310" width="140" height="100" rx="12" fill="{GREEN}"/>
  <text x="350" y="370" text-anchor="middle" fill="{WHITE}" font-size="18" font-family="Georgia, serif">RAN</text>
  <!-- 5GC -->
  <rect x="480" y="250" width="200" height="80" rx="12" fill="{RED}"/>
  <text x="580" y="300" text-anchor="middle" fill="{WHITE}" font-size="18" font-family="Georgia, serif">5GC</text>
  <!-- IMS -->
  <rect x="480" y="360" width="200" height="80" rx="12" fill="{GOLD}"/>
  <text x="580" y="410" text-anchor="middle" fill="{BLACK}" font-size="18" font-family="Georgia, serif">Elisa IMS</text>
  <!-- CAMARA -->
  <rect x="760" y="250" width="200" height="80" rx="12" fill="{GREEN}"/>
  <text x="860" y="300" text-anchor="middle" fill="{WHITE}" font-size="16" font-family="Georgia, serif">CAMARA SA</text>
  <!-- R -->
  <rect x="760" y="360" width="200" height="80" rx="12" fill="{BLACK}" stroke="{GOLD}" stroke-width="2"/>
  <text x="860" y="410" text-anchor="middle" fill="{GOLD}" font-size="18" font-family="Georgia, serif">Analytics R</text>
  <!-- Banks -->
  <rect x="1020" y="300" width="180" height="100" rx="12" fill="{CREAM}"/>
  <text x="1110" y="360" text-anchor="middle" fill="{DARK}" font-size="16" font-family="Georgia, serif">Banks / Apps</text>
  <line x1="215" y1="360" x2="280" y2="360" stroke="{GOLD}" stroke-width="3"/>
  <line x1="420" y1="360" x2="480" y2="360" stroke="{GOLD}" stroke-width="3"/>
  <line x1="680" y1="290" x2="760" y2="290" stroke="{GOLD}" stroke-width="3"/>
  <line x1="680" y1="400" x2="760" y2="400" stroke="{GOLD}" stroke-width="3"/>
  <line x1="960" y1="350" x2="1020" y2="350" stroke="{GOLD}" stroke-width="3"/>
  <text x="80" y="560" fill="#C8E6D0" font-size="16" font-family="Helvetica, Arial, sans-serif">
    Voice/data sessions stay on-net · Silent auth APIs monetize trust for banks &amp; fintech
  </text>
</svg>
""",
    )


def svg_vas() -> None:
    items = [
        ("Voice / VoLTE", "Elisa IMS"),
        ("SMS / USSD", "Gateway VAS"),
        ("Silent Auth", "CAMARA"),
        ("eKYC Assist", "Trust APIs"),
        ("IoT Profiles", "Slice-ready"),
        ("Enterprise", "Private APN"),
    ]
    parts = []
    for i, (t, s) in enumerate(items):
        x = 80 + (i % 3) * 380
        y = 180 + (i // 3) * 220
        parts.append(
            f"""
  <rect x="{x}" y="{y}" width="340" height="180" rx="16" fill="{WHITE}" stroke="{GREEN}" stroke-width="3"/>
  <circle cx="{x + 50}" cy="{y + 50}" r="22" fill="{GOLD}"/>
  <text x="{x + 50}" y="{y + 58}" text-anchor="middle" font-size="18" fill="{BLACK}" font-family="Georgia, serif">{i+1}</text>
  <text x="{x + 90}" y="{y + 58}" font-size="22" fill="{DARK}" font-family="Georgia, serif">{t}</text>
  <text x="{x + 40}" y="{y + 120}" font-size="16" fill="{MUTED}" font-family="Helvetica, Arial, sans-serif">{s}</text>
"""
        )
    write_svg(
        "10_vas",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">VAS Portfolio</text>
  <text x="80" y="140" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">Services that lift ARPU beyond commodity minutes</text>
  {''.join(parts)}
</svg>
""",
    )


def svg_camara() -> None:
    write_svg(
        "11_camara",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{DARK}" font-weight="700">CAMARA Silent Authentication</text>
  <text x="80" y="145" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">
    Replace OTA-heavy SMS OTP flows with network-native, SIM-grade identity
  </text>
  <!-- Old -->
  <rect x="80" y="200" width="500" height="400" rx="18" fill="#F5E8E8" stroke="{RED}" stroke-width="3"/>
  <text x="330" y="250" text-anchor="middle" font-size="24" fill="{RED}" font-family="Georgia, serif">Legacy OTA / OTP</text>
  <text x="120" y="310" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• SMS delivery risk (SS7)</text>
  <text x="120" y="350" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• SIM toolkit / OTA complexity</text>
  <text x="120" y="390" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• High fraud &amp; cost per auth</text>
  <text x="120" y="430" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• Poor UX (wait for code)</text>
  <!-- New -->
  <rect x="700" y="200" width="500" height="400" rx="18" fill="#E8F7EE" stroke="{GREEN}" stroke-width="3"/>
  <text x="950" y="250" text-anchor="middle" font-size="24" fill="{GREEN}" font-family="Georgia, serif">CAMARA SA</text>
  <text x="740" y="310" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• Network / SIM-based proof</text>
  <text x="740" y="350" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• GSMA Open Gateway APIs</text>
  <text x="740" y="390" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• Banks &amp; fintech pay per verify</text>
  <text x="740" y="430" font-size="16" fill="{DARK}" font-family="Helvetica, Arial, sans-serif">• Instant UX · lower fraud</text>
  <polygon points="620,400 680,380 680,420" fill="{GOLD}"/>
</svg>
""",
    )


def svg_camara_flow() -> None:
    steps = ["App", "Bank API", "CAMARA", "5GC/IMS", "Result"]
    parts = []
    for i, s in enumerate(steps):
        x = 80 + i * 230
        fill = [GREEN, GOLD, RED, MUTED, BLACK][i]
        fg = BLACK if fill == GOLD else WHITE
        parts.append(
            f"""
  <rect x="{x}" y="300" width="180" height="100" rx="14" fill="{fill}"/>
  <text x="{x + 90}" y="360" text-anchor="middle" fill="{fg}" font-size="20" font-family="Georgia, serif">{s}</text>
"""
        )
        if i < len(steps) - 1:
            parts.append(
                f'<line x1="{x + 180}" y1="350" x2="{x + 230}" y2="350" stroke="{DARK}" stroke-width="3"/>'
            )
    write_svg(
        "12_camara_flow",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{DARK}" font-weight="700">Why CAMARA for Zimbabwe</text>
  <text x="80" y="150" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">
    Digital payments &amp; e-government need trusted MSISDN binding — without OTA ops burden
  </text>
  {''.join(parts)}
  <rect x="80" y="480" width="1120" height="140" rx="14" fill="{DARK}"/>
  <text x="120" y="540" fill="{GOLD}" font-size="20" font-family="Helvetica, Arial, sans-serif">
    Policy choice: deploy CAMARA Silent Authentication as the primary VAS identity product —
  </text>
  <text x="120" y="575" fill="{WHITE}" font-size="18" font-family="Helvetica, Arial, sans-serif">
    OTA SMSC-GW remains optional for niche SIM campaigns, not the auth path for banks.
  </text>
</svg>
""",
    )


def svg_analytics_r() -> None:
    # radar-ish / dashboard tiles
    write_svg(
        "13_analytics",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{DARK}" font-weight="700">Analytics R — Profitability Engine</text>
  <text x="80" y="145" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">
    Real-time unit economics so the MVNO stays profitable by design
  </text>
  <rect x="80" y="190" width="360" height="220" rx="16" fill="{GREEN}"/>
  <text x="110" y="250" fill="{WHITE}" font-size="18" font-family="Helvetica, Arial, sans-serif">ARPU / subscriber</text>
  <text x="110" y="320" fill="{GOLD}" font-size="48" font-family="Georgia, serif">Live</text>
  <rect x="460" y="190" width="360" height="220" rx="16" fill="{GOLD}"/>
  <text x="490" y="250" fill="{BLACK}" font-size="18" font-family="Helvetica, Arial, sans-serif">Wholesale cost / GB</text>
  <text x="490" y="320" fill="{RED}" font-size="48" font-family="Georgia, serif">Guard</text>
  <rect x="840" y="190" width="360" height="220" rx="16" fill="{RED}"/>
  <text x="870" y="250" fill="{WHITE}" font-size="18" font-family="Helvetica, Arial, sans-serif">VAS auth margin</text>
  <text x="870" y="320" fill="{GOLD}" font-size="48" font-family="Georgia, serif">Scale</text>
  <rect x="80" y="440" width="1120" height="180" rx="16" fill="{DARK}"/>
  <text x="120" y="510" fill="{GOLD}" font-size="22" font-family="Georgia, serif">R feeds pricing, churn, and capacity decisions daily</text>
  <text x="120" y="555" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">
    CDR · session · CAMARA verify · wholesale invoices → contribution margin by segment
  </text>
</svg>
""",
    )


def svg_unit_economics() -> None:
    write_svg(
        "14_economics",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{DARK}" font-weight="700">Unit Economics (Illustrative)</text>
  <!-- waterfall-ish -->
  <rect x="100" y="200" width="160" height="320" rx="10" fill="{GREEN}"/>
  <text x="180" y="360" text-anchor="middle" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">Revenue</text>
  <rect x="300" y="280" width="160" height="240" rx="10" fill="{RED}"/>
  <text x="380" y="400" text-anchor="middle" fill="{WHITE}" font-size="14" font-family="Helvetica, Arial, sans-serif">Wholesale</text>
  <rect x="500" y="320" width="160" height="200" rx="10" fill="{MUTED}"/>
  <text x="580" y="420" text-anchor="middle" fill="{WHITE}" font-size="14" font-family="Helvetica, Arial, sans-serif">OPEX</text>
  <rect x="700" y="260" width="160" height="260" rx="10" fill="{GOLD}"/>
  <text x="780" y="390" text-anchor="middle" fill="{BLACK}" font-size="14" font-family="Helvetica, Arial, sans-serif">VAS lift</text>
  <rect x="900" y="220" width="200" height="300" rx="10" fill="{BLACK}"/>
  <text x="1000" y="370" text-anchor="middle" fill="{GOLD}" font-size="18" font-family="Georgia, serif">Margin</text>
  <text x="80" y="580" font-size="16" fill="{MUTED}" font-family="Helvetica, Arial, sans-serif">
    CAMARA verify fees + smart data bundles offset wholesale floor — R enforces floor prices.
  </text>
</svg>
""",
    )


def svg_dashboard() -> None:
    write_svg(
        "15_dashboard",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{DARK}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{WHITE}" font-weight="700">Profitability Dashboard Concept</text>
  <!-- sparkline fake -->
  <polyline points="120,400 200,360 280,380 360,300 440,320 520,250 600,280 680,220 760,240 840,180 920,200 1000,160 1080,190 1160,140"
            fill="none" stroke="{GOLD}" stroke-width="4"/>
  <polyline points="120,450 200,440 280,455 360,430 440,445 520,420 600,435 680,410 760,425 840,400 920,415 1000,390 1080,405 1160,380"
            fill="none" stroke="{GREEN}" stroke-width="3"/>
  <text x="120" y="520" fill="{GOLD}" font-size="16" font-family="Helvetica, Arial, sans-serif">Contribution margin (gold)</text>
  <text x="120" y="550" fill="{GREEN}" font-size="16" font-family="Helvetica, Arial, sans-serif">Active subscribers (green)</text>
  <text x="120" y="620" fill="#C8E6D0" font-size="16" font-family="Helvetica, Arial, sans-serif">
    Alerts when margin &lt; threshold · auto-suggest tariff / throttle / VAS push
  </text>
</svg>
""",
    )


def svg_roadmap() -> None:
    phases = [
        ("Q1", "Pilot IMS + 5GC lab", GREEN),
        ("Q2", "CAMARA SA banks", GOLD),
        ("Q3", "Commercial MVNO", RED),
        ("Q4", "Nationwide scale + R", BLACK),
    ]
    parts = []
    for i, (q, t, c) in enumerate(phases):
        x = 80 + i * 295
        fg = BLACK if c == GOLD else WHITE
        parts.append(
            f"""
  <rect x="{x}" y="280" width="270" height="220" rx="16" fill="{c}"/>
  <text x="{x + 135}" y="350" text-anchor="middle" fill="{fg}" font-size="36" font-family="Georgia, serif">{q}</text>
  <text x="{x + 135}" y="420" text-anchor="middle" fill="{fg}" font-size="16" font-family="Helvetica, Arial, sans-serif">{t}</text>
"""
        )
    write_svg(
        "16_roadmap",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="40" fill="{DARK}" font-weight="700">Implementation Roadmap</text>
  <text x="80" y="150" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="{MUTED}">12-month path from lab to profitable national MVNO</text>
  <line x1="80" y1="250" x2="1200" y2="250" stroke="{GOLD}" stroke-width="6"/>
  {''.join(parts)}
</svg>
""",
    )


def svg_security() -> None:
    write_svg(
        "17_security",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{CREAM}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{DARK}" font-weight="700">Security &amp; Compliance</text>
  <rect x="80" y="180" width="350" height="400" rx="16" fill="{GREEN}"/>
  <text x="110" y="250" fill="{WHITE}" font-size="22" font-family="Georgia, serif">Signalling</text>
  <text x="110" y="310" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">IMS AKA · Diameter Cx</text>
  <text x="110" y="350" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">5G AUSF / UDM</text>
  <text x="110" y="390" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">Dialog HA / recovery</text>
  <rect x="465" y="180" width="350" height="400" rx="16" fill="{GOLD}"/>
  <text x="495" y="250" fill="{BLACK}" font-size="22" font-family="Georgia, serif">Identity</text>
  <text x="495" y="310" fill="{BLACK}" font-size="16" font-family="Helvetica, Arial, sans-serif">CAMARA SA APIs</text>
  <text x="495" y="350" fill="{BLACK}" font-size="16" font-family="Helvetica, Arial, sans-serif">Consent &amp; audit logs</text>
  <text x="495" y="390" fill="{BLACK}" font-size="16" font-family="Helvetica, Arial, sans-serif">Bank-grade verify</text>
  <rect x="850" y="180" width="350" height="400" rx="16" fill="{RED}"/>
  <text x="880" y="250" fill="{WHITE}" font-size="22" font-family="Georgia, serif">Nation</text>
  <text x="880" y="310" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">Data residency ZW</text>
  <text x="880" y="350" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">POTRAZ / regulator</text>
  <text x="880" y="390" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">Lawful intercept ready</text>
</svg>
""",
    )


def svg_benefits() -> None:
    write_svg(
        "18_benefits",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{DARK}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{WHITE}" font-weight="700">Benefits for Government &amp; Citizens</text>
  <g>
    <circle cx="220" cy="320" r="100" fill="{GREEN}"/>
    <text x="220" y="315" text-anchor="middle" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">Jobs &amp;</text>
    <text x="220" y="340" text-anchor="middle" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">skills</text>
  </g>
  <g>
    <circle cx="480" cy="320" r="100" fill="{GOLD}"/>
    <text x="480" y="315" text-anchor="middle" fill="{BLACK}" font-size="16" font-family="Helvetica, Arial, sans-serif">Digital</text>
    <text x="480" y="340" text-anchor="middle" fill="{BLACK}" font-size="16" font-family="Helvetica, Arial, sans-serif">payments</text>
  </g>
  <g>
    <circle cx="740" cy="320" r="100" fill="{RED}"/>
    <text x="740" y="315" text-anchor="middle" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">Sovereign</text>
    <text x="740" y="340" text-anchor="middle" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">core</text>
  </g>
  <g>
    <circle cx="1000" cy="320" r="100" fill="{BLACK}" stroke="{GOLD}" stroke-width="4"/>
    <text x="1000" y="315" text-anchor="middle" fill="{GOLD}" font-size="16" font-family="Helvetica, Arial, sans-serif">Profitable</text>
    <text x="1000" y="340" text-anchor="middle" fill="{GOLD}" font-size="16" font-family="Helvetica, Arial, sans-serif">MVNO</text>
  </g>
  <text x="80" y="520" fill="#C8E6D0" font-size="18" font-family="Helvetica, Arial, sans-serif">
    Citizens get cheaper, safer connectivity · Government gets a digital trust layer for e-services.
  </text>
</svg>
""",
    )


def svg_partnership() -> None:
    write_svg(
        "19_partnership",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <rect width="1280" height="720" fill="{WHITE}"/>
  {flag_strip(0, 24)}
  <text x="80" y="100" font-family="Georgia, serif" font-size="36" fill="{DARK}" font-weight="700">Partnership &amp; Investment Model</text>
  <rect x="80" y="180" width="540" height="400" rx="18" fill="{GREEN}"/>
  <text x="120" y="250" fill="{GOLD}" font-size="24" font-family="Georgia, serif">Government</text>
  <text x="120" y="310" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Licence / policy enablement</text>
  <text x="120" y="350" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Wholesale facilitation</text>
  <text x="120" y="390" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Data residency mandate</text>
  <text x="120" y="430" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Anchor e-gov tenants</text>
  <rect x="660" y="180" width="540" height="400" rx="18" fill="{DARK}"/>
  <text x="700" y="250" fill="{GOLD}" font-size="24" font-family="Georgia, serif">Technology Partner</text>
  <text x="700" y="310" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Elisa IMS + 5G Core build</text>
  <text x="700" y="350" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· CAMARA Silent Auth VAS</text>
  <text x="700" y="390" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Analytics R for margin</text>
  <text x="700" y="430" fill="{WHITE}" font-size="16" font-family="Helvetica, Arial, sans-serif">· Knowledge transfer</text>
</svg>
""",
    )


def svg_cta() -> None:
    star = star_points(640, 280, 60, 26)
    write_svg(
        "20_cta",
        f"""
<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">
  <defs>
    <linearGradient id="bg2" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="{GREEN}"/>
      <stop offset="100%" stop-color="{DARK}"/>
    </linearGradient>
  </defs>
  <rect width="1280" height="720" fill="url(#bg2)"/>
  {flag_strip(0, 36)}
  <polygon points="{star}" fill="{GOLD}" stroke="{RED}" stroke-width="3"/>
  <text x="640" y="420" text-anchor="middle" font-family="Georgia, serif" font-size="40" fill="{WHITE}" font-weight="700">
    Next Step: Joint Steering Committee
  </text>
  <text x="640" y="480" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="20" fill="{GOLD}">
    Approve pilot · Host-MNO MoU · Bank CAMARA sandbox · R KPI pack
  </text>
  <text x="640" y="560" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="16" fill="#C8E6D0">
    Zimbabwe MVNO — sovereign, profitable, trusted
  </text>
  {flag_strip(684, 36)}
</svg>
""",
    )


SLIDE_META = [
    ("01_cover", "Cover", None),  # full-bleed image slides
    ("02_agenda", "Agenda", None),
    ("03_vision", "Vision", None),
    ("04_market", "Market", None),
    ("05_mvno", "MVNO Model", None),
    ("06_architecture", "Architecture", None),
    ("07_elisa", "Elisa IMS", None),
    ("08_5gc", "5G Core", None),
    ("09_topology", "Topology", None),
    ("10_vas", "VAS", None),
    ("11_camara", "CAMARA vs OTA", None),
    ("12_camara_flow", "CAMARA Why", None),
    ("13_analytics", "Analytics R", None),
    ("14_economics", "Unit Economics", None),
    ("15_dashboard", "Dashboard", None),
    ("16_roadmap", "Roadmap", None),
    ("17_security", "Security", None),
    ("18_benefits", "Benefits", None),
    ("19_partnership", "Partnership", None),
    ("20_cta", "Call to Action", None),
]


def build_pptx() -> None:
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank = prs.slide_layouts[6]

    for name, _title, _ in SLIDE_META:
        png = PNG_DIR / f"{name}.png"
        slide = prs.slides.add_slide(blank)
        # full-bleed graphic
        slide.shapes.add_picture(str(png), Inches(0), Inches(0), width=prs.slide_width)

        # thin gold accent bar at bottom for consistency (on top of image edge)
        bar = slide.shapes.add_shape(
            MSO_SHAPE.RECTANGLE,
            Inches(0),
            Inches(7.42),
            prs.slide_width,
            Inches(0.08),
        )
        bar.fill.solid()
        bar.fill.fore_color.rgb = RGB["gold"]
        bar.line.fill.background()

    prs.save(OUT)
    print(f"Wrote {OUT}")


def main() -> None:
    SVG_DIR.mkdir(parents=True, exist_ok=True)
    PNG_DIR.mkdir(parents=True, exist_ok=True)

    generators = [
        svg_cover,
        svg_agenda,
        svg_vision,
        svg_market,
        svg_mvno_model,
        svg_architecture,
        svg_elisa,
        svg_5gc,
        svg_topology,
        svg_vas,
        svg_camara,
        svg_camara_flow,
        svg_analytics_r,
        svg_unit_economics,
        svg_dashboard,
        svg_roadmap,
        svg_security,
        svg_benefits,
        svg_partnership,
        svg_cta,
    ]
    for gen in generators:
        gen()

    for name, _, _ in SLIDE_META:
        svg = SVG_DIR / f"{name}.svg"
        png = PNG_DIR / f"{name}.png"
        svg_to_png(svg, png, width=1920)
        print(f"PNG {png.name}")

    build_pptx()


if __name__ == "__main__":
    main()
