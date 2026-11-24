# Policy / charging (Gx / Gy / Gz)

Pilot: static PCC + offline CDR from day one (`PcefService`). Prepaid is **fail-closed** until Gy OCS `ocsLive=true`. Differentiated QoS and VoLTE QCI 1 wait on live PCRF/Gx (and Elisa Rx).

Reconciliation: UP usage octets must equal Gz CDR octets for the same charging-id across retransmission, modify-bearer, and failover. No per-packet database writes.
