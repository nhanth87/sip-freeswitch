package et.epc.hss;

/** TS 29.272 command codes (Application-Id 16777251). */
public enum S6aCommand {
    AIR(318), AIA(318),
    ULR(316), ULA(316),
    CLR(317), CLA(317),
    PUR(321), PUA(321),
    IDR(319), IDA(319),
    DSR(320), DSA(320),
    RSR(322), RSA(322),
    NOR(323), NOA(323);

    public static final long APP_ID = 16_777_251L;
    public final int code;

    S6aCommand(int code) {
        this.code = code;
    }
}
