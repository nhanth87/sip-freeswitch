# IMS APN + Rx (not a VoLTE claim)

PGW exposes APN `ims` and P-CSCF address (see `ApnConfig.ims`). Elisa P-CSCF is the Gm peer.

**No VoLTE QoS claim** until Elisa P-CSCF originates Diameter **Rx** (TS 29.214) toward PCRF and a QCI 1 dedicated bearer is proven on the wire.

Current Elisa grill: Rx is out; `ims-core.md` forbids VoLTE QoS claims without Rx.

Production Cx: Elisa `SF_IMS_HSS=false` + `SF_IMS_CX_DEST_HOST=epc-hss` ([deploy/elisa/roles/scscf-cx-epc.env](../../../deploy/elisa/roles/scscf-cx-epc.env)). Lab keeps HSS-lite.
