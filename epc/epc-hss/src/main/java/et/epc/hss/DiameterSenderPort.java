package et.epc.hss;

import com.microjainslee.api.OutboundCommand;

/**
 * Server-initiated Diameter emission seam — the bridge between the HSS/Cx procedure
 * layer and the vendor Diameter RA command port ({@code RaCommandPort.sendCommand}).
 * {@link HssService} / {@link CxService} build {@code SendDiameterRequest} commands and
 * push them through this port; the SBB wires it to {@code diameter-ra}. Keeping the
 * seam interface (not a concrete RA) in {@code epc-hss} lets the emit logic be unit-tested
 * without a container.
 */
@FunctionalInterface
public interface DiameterSenderPort {

    void send(OutboundCommand command);
}