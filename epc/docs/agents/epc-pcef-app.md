# EPC PCEF — standalone application (`epc-pcef`)

`epc-pcef` is first a **library** (`PcefService` / `CdrStore` / `JdbcCdrStore`,
`PcrfClient`, …). It can additionally bootstrap as its **own micro-jainslee
service** via the `app/` package.

## Rule

> **PCEF standalone runs the decision core; Gx transport is wired via `PcrfPort`
> in host.**

The standalone app runs the policy-and-charging **decision core** only
(`PcefService`). It binds no Diameter socket and establishes no S5/Sxb procedures.
The Gx transport (`ra-diameter`) is attached by the host image through the
`PcrfPort`-style delegation seam — `PcefSbb.handle(DiameterRequestEvent)`,
identical to how `epc-hss`/`epc-pgwc` delegate Gx/Rx to `epc-pcrf`.

## New artifacts

| File | Role |
|------|------|
| `app/PcefApp.java` | micro-jainslee app bootstrap: registers `PcefSbb` and maps `DiameterRequestEvent` (real `ra-diameter` class) to it. `wired()` records the bindings for tests. |
| `app/PcefMain.java` | embedded main (`MicroSleeContainer` + `PcefApp` + heap `PcefService`). R&D mode — no transport. |
| `sbbs/PcefSbb.java` | SBB owning the decision flow. Business surface: `openSession`/`authorize`/`reportUsage`/`close` (delegate to `PcefService`). Gx rail: `handle(DiameterRequestEvent)` acknowledges the only PCRF-initiated Gx request, the RAR (258), with a successful RAA; non-Gx is ignored. |
| `test/.../app/PcefAppTest.java` | asserts the SBB type + `DiameterRequestEvent` mapping are registered. |
| `test/.../sbbs/PcefSbbTest.java` | unit-test the decision flow directly (no container) — `openSession`/`authorize`/`reportUsage`/`close`, RAR→RAA, unknown-Gx→error, non-Gx→ignored. |

## Gx model (why a request map exists at all)

In Gx (TS 29.212) the **PCEF is the Diameter client** (CCR initiator). The only
Diameter *request* a PCRF sends to a PCEF is the server-initiated
**Re-Auth-Request (RAR)**; the standalone SBB acknowledges it with `DIAMETER_SUCCESS`.
Business decisions still come from `PcefService` (static fallback by APN, or the
injected `PcrfClient` when `pcrfLive`).

## Packaging note

Inside the PGW-C host (`epc-pgwc` `PgwSbb`/`PgwControl`) PCEF is *not* a separate
app — it runs embedded, so the `app/` package is only exercised in dedicated
standalone deployments.

Build (JDK 25, mise `zulu-25`):

```bash
cd epc && mvn -q -pl epc-pcef -am test
```