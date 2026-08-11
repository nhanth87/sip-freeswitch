package et.elisa.ims.mmtel;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.persist.ImsMmtelSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MMTel-lite (Clearwater Sprout built-in AS subset): barring, diversion, privacy (OIP/OIR lite).
 * Applied in S-CSCF before ISC.
 */
@ApplicationScoped
public class MmtelService {

    private static final Logger LOG = LogManager.getLogger(MmtelService.class);

    public enum Action {
        CONTINUE,
        BARRED,
        DIVERT
    }

    public record Decision(Action action, String divertUri, int sipStatus, String reason) {
        public static Decision cont() {
            return new Decision(Action.CONTINUE, null, 0, null);
        }

        public static Decision barred() {
            return new Decision(Action.BARRED, null, 603, "Decline");
        }

        public static Decision divert(String uri) {
            return new Decision(Action.DIVERT, uri, 0, null);
        }
    }

    @Inject ImsRoleFlags roles;

    @Transactional
    public Decision evaluate(String fromUri, String toUri, boolean terminating) {
        if (roles != null && !roles.featureMmtel()) {
            return Decision.cont();
        }
        String key = terminating ? toUri : fromUri;
        Optional<ImsMmtelSettings> opt = find(key);
        if (opt.isEmpty()) {
            return Decision.cont();
        }
        ImsMmtelSettings s = opt.get();
        if (terminating && s.barringMt) {
            LOG.debug("[MMTel] MT barring impu={}", s.impu);
            return Decision.barred();
        }
        if (!terminating && s.barringMo) {
            LOG.debug("[MMTel] MO barring impu={}", s.impu);
            return Decision.barred();
        }
        if (terminating && s.diversionUri != null && !s.diversionUri.isBlank()) {
            LOG.debug("[MMTel] diversion {} -> {}", s.impu, s.diversionUri);
            return Decision.divert(s.diversionUri.trim());
        }
        return Decision.cont();
    }

    /** OIR/privacy: strip PAI and set Privacy: id when subscriber opted in. */
    @Transactional
    public void applyPrivacy(Map<String, List<String>> imsHeaders, String fromUri) {
        if (roles != null && !roles.featureMmtel()) {
            return;
        }
        if (imsHeaders == null || fromUri == null) {
            return;
        }
        find(fromUri).ifPresent(s -> {
            if (!s.privacyId) {
                return;
            }
            imsHeaders.remove("P-Asserted-Identity");
            imsHeaders.put("Privacy", List.of("id"));
            LOG.debug("[MMTel] privacy id for {}", s.impu);
        });
    }

    Optional<ImsMmtelSettings> find(String uriOrImpu) {
        if (uriOrImpu == null || uriOrImpu.isBlank()) {
            return Optional.empty();
        }
        try {
            ImsMmtelSettings exact = ImsMmtelSettings.find("impu", uriOrImpu.trim()).firstResult();
            if (exact != null) {
                return Optional.of(exact);
            }
            String norm = normalizeImpu(uriOrImpu);
            if (!norm.equals(uriOrImpu.trim())) {
                exact = ImsMmtelSettings.find("impu", norm).firstResult();
                if (exact != null) {
                    return Optional.of(exact);
                }
            }
        } catch (RuntimeException ex) {
            // unit tests / no Panache
        }
        return Optional.empty();
    }

    static String normalizeImpu(String uri) {
        String s = uri.trim();
        if (!s.toLowerCase(Locale.ROOT).startsWith("sip:") && !s.toLowerCase(Locale.ROOT).startsWith("tel:")) {
            return "sip:" + s;
        }
        return s;
    }
}
