package et.elisa.ivr;

import et.elisa.admin.FsConfService;
import et.elisa.esl.EslClient;
import et.elisa.persist.SfIvrEntry;
import et.elisa.persist.SfIvrMenu;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * T9 optional: write IVR dialplan under conf jail + ESL {@code reloadxml}.
 * Primary path remains xml_curl ({@code /admin/ivr/xml/...}).
 */
@ApplicationScoped
public class IvrPublishService {

    private static final Logger LOG = LogManager.getLogger(IvrPublishService.class);

    @Inject FsConfService conf;
    @Inject EslClient esl;

    @ConfigProperty(name = "sf.ivr.conf-export-enabled", defaultValue = "true")
    boolean exportEnabled;

    @ConfigProperty(name = "sf.ivr.conf-subdir", defaultValue = "dialplan/elisa-ivr")
    String confSubdir;

    public Map<String, Object> publish(SfIvrMenu menu) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        List<SfIvrEntry> entries = SfIvrEntry.list("menuId", menu.id);
        String xml = IvrXmlBuilder.build(menu, entries);
        out.put("xmlCurlPath", "/admin/ivr/xml/" + menu.tenantId + "/" + menu.name);
        out.put("bytes", xml.length());

        if (!exportEnabled) {
            out.put("confExport", false);
            out.put("message", "conf export disabled (xml_curl only)");
            return out;
        }
        if (!conf.confRootExists()) {
            out.put("confExport", false);
            out.put("message", "conf root missing — xml_curl only");
            return out;
        }

        String safeTenant = sanitize(menu.tenantId);
        String safeName = sanitize(menu.name);
        String dir = confSubdir.endsWith("/") ? confSubdir.substring(0, confSubdir.length() - 1) : confSubdir;
        String relative = dir + "/" + safeTenant + "-" + safeName + ".xml";
        try {
            conf.mkdir(dir);
        } catch (IOException exists) {
            // already there
        }
        conf.writeFile(relative, xml);
        String reload = esl.api("reloadxml");
        out.put("confExport", true);
        out.put("path", relative);
        out.put("reloadxml", reload);
        out.put("message", "exported + reloadxml");
        LOG.info("ivr_publish tenant=%s name=%s path=%s", menu.tenantId, menu.name, relative);
        return out;
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "x";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
}
