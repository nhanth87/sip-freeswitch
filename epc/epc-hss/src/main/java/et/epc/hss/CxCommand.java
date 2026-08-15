package et.epc.hss;

/** TS 29.229 Cx commands (Application-Id 16777216). Separate from S6a registration state. */
public enum CxCommand {
    UAR(300), UAA(300),
    SAR(301), SAA(301),
    LIR(302), LIA(302),
    MAR(303), MAA(303),
    RTR(304), RTA(304),
    PPR(305), PPA(305);

    public static final long APP_ID = 16_777_216L;
    public final int code;

    CxCommand(int code) {
        this.code = code;
    }
}
