# Reza brief — Africa MVNO pitch deck

Commercial deck only. **Lab ≠ deck.** Engineering truth (Elisa, FreeSWITCH, Quarkus, HSS-lite, CPS targets) stays in `docs/agents/` and code — never on slides.

## Audience constraints (locked 2026-08-10+)

| Rule | Detail |
|------|--------|
| Cover | Light: `MVNO` + `IMS · VAS · Digital Identity` only. No government, country, or vendor stack. |
| Theme | Pan-African Sahel + textile chevron — **not** a national flag. |
| Slide 7 (IMS Core) | Media = **MRF / AS** only. No Lab-Grade. |
| Product names | Never Elisa / FreeSWITCH on any slide. |
| Capacity | No TPS / CPS / “100k / 100 servers” fiction. |
| CAMARA | Keep; do **not** claim ETSI/GSMA certification. |

## Forbidden words (fail-closed)

Scan generator + `svg/` + unzipped PPTX XML before handoff:

- `Elisa`, `ELISA`
- `FreeSWITCH`
- `Lab-Grade`
- `Zimbabwe`, `Government of`, `POTRAZ`
- `micro-jainslee`, `Quarkus` (on slide text)
- `HSS-lite` (use `HSS` or “subscriber database”)
- `ICE/TURN`, `High CPS`, `not an SBC` (customer jargon cut)

Comments in the generator that mention forbidden names as *constraints* are OK; slide SVG/PNG text is not.

## Allowed IMS language

- P-CSCF / I-CSCF / S-CSCF / HSS
- MRF / AS, session control, media path, operator admin
- CAMARA Silent Auth, VAS, Analytics R
- 5GC NF names (AMF/SMF/UPF/…)

## Regenerate

```bash
cd docs/presentations/africa-mvno-pitch
python3 generate_africa_mvno_ppt.py
# → IMS_VAS_MVNO_Pitch.pptx
```

Requires `rsvg-convert` and `python-pptx`.

## Grill locks (summary)

1. Two surfaces: deck vs lab  
2. No capacity fiction on deck  
3. HSS wording — no lite / no false certification  
4. Jargon cut (ICE/TURN, CPS, SBC)  
5. CSCF labels OK (standard IMS)  
6. Path must not contain `zimbabwe`  
7. CAMARA kept without cert claims  
8. No Java/Quarkus diagrams on slides  
