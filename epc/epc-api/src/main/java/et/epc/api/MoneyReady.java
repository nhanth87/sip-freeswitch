package et.epc.api;

/** Link-status truth helper: local bind is never "ready". */
public record MoneyReady(boolean peerReady, String reason) {
    public static MoneyReady down(String reason) {
        return new MoneyReady(false, reason);
    }

    public static MoneyReady up() {
        return new MoneyReady(true, "peer-ready");
    }
}
