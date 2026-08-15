package et.epc.hss;

import et.epc.api.Imsi;

/** Durable subscriber PK — not in-flight auth-vector identity. */
public record SubscriberRecordId(Imsi imsi) {}
