package et.epc.api;

import java.util.Objects;

/**
 * PLMN as MCC/MNC plus NAS/S6a 3-octet SN-id encoding (TS 24.301 / TS 23.003).
 */
public record PlmnId(String mcc, String mnc) {

    public PlmnId {
        Objects.requireNonNull(mcc);
        Objects.requireNonNull(mnc);
        if (mcc.length() != 3 || !mcc.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("MCC must be 3 digits");
        }
        if (!(mnc.length() == 2 || mnc.length() == 3) || !mnc.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("MNC must be 2 or 3 digits");
        }
    }

    /** 3-octet SN id: MCC2|MCC1, MNC3-or-F|MCC3, MNC2|MNC1. */
    public byte[] snId() {
        int mcc1 = mcc.charAt(0) - '0';
        int mcc2 = mcc.charAt(1) - '0';
        int mcc3 = mcc.charAt(2) - '0';
        int mnc1 = mnc.charAt(0) - '0';
        int mnc2 = mnc.charAt(1) - '0';
        int mnc3 = mnc.length() == 3 ? mnc.charAt(2) - '0' : 0xF;
        return new byte[] {
                (byte) ((mcc2 << 4) | mcc1),
                (byte) ((mnc3 << 4) | mcc3),
                (byte) ((mnc2 << 4) | mnc1)
        };
    }

    public String diameterRealm() {
        String paddedMnc = mnc.length() == 2 ? "0" + mnc : mnc;
        return "epc.mnc" + paddedMnc + ".mcc" + mcc + ".3gppnetwork.org";
    }
}
