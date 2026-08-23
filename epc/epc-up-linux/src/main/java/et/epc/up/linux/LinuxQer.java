package et.epc.up.linux;

import et.epc.api.QosProfile;
import java.util.Objects;

/** QER (QoS enforcement rule) MBR/GBR rates in bits per second, per direction. */
public record LinuxQer(String qerId, long mbrUlBps, long mbrDlBps, long gbrUlBps, long gbrDlBps) {

    public LinuxQer {
        Objects.requireNonNull(qerId, "qerId");
        requireNonNegative("mbrUlBps", mbrUlBps);
        requireNonNegative("mbrDlBps", mbrDlBps);
        requireNonNegative("gbrUlBps", gbrUlBps);
        requireNonNegative("gbrDlBps", gbrDlBps);
        if (gbrUlBps > mbrUlBps) {
            throw new IllegalArgumentException("gbrUlBps must not exceed mbrUlBps");
        }
        if (gbrDlBps > mbrDlBps) {
            throw new IllegalArgumentException("gbrDlBps must not exceed mbrDlBps");
        }
    }

    public static LinuxQer of(String qerId, long mbrUlBps, long mbrDlBps, long gbrUlBps, long gbrDlBps) {
        return new LinuxQer(qerId, mbrUlBps, mbrDlBps, gbrUlBps, gbrDlBps);
    }

    /**
     * Coarse P1 mapping from the existing {@link QosProfile}: MBR = UE-AMBR and the
     * guaranteed floor = APN-AMBR clamped to MBR. True per-QCI GBR comes later, since
     * {@link QosProfile} carries no per-QCI bitrate table.
     */
    public static LinuxQer fromQos(String qerId, QosProfile qos) {
        Objects.requireNonNull(qos, "qos");
        long gbrUl = Math.min(qos.apnAmbrUlBps(), qos.ueAmbrUlBps());
        long gbrDl = Math.min(qos.apnAmbrDlBps(), qos.ueAmbrDlBps());
        return new LinuxQer(qerId, qos.ueAmbrUlBps(), qos.ueAmbrDlBps(), gbrUl, gbrDl);
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}