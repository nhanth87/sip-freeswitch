package et.epc.hss;

import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;

/**
 * PCRF/PCEF (Gx/Rx) delegation seam. {@link sbbs.HssSbb} forwards Gx (16777238)
 * and Rx (16777236) application requests here. The lead binds {@code epc-pcrf}'s
 * Gx/Rx handler behind this port; the default lab can leave it unbound and the
 * SBB answers with an application-dependent error result code.
 */
@FunctionalInterface
public interface PcrfPort {

    SendDiameterAnswer handle(DiameterRequestEvent req);
}