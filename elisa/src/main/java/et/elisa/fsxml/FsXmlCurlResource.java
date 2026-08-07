package et.elisa.fsxml;

import et.elisa.persist.SfDialplanRule;
import et.elisa.persist.SfExtension;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** FreeSWITCH mod_xml_curl endpoint — directory/dialplan from Postgres when present. */
@Path("/fs/xml")
public class FsXmlCurlResource {

    @POST
    @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_FORM_URLENCODED,
            MediaType.TEXT_PLAIN, MediaType.WILDCARD})
    @Produces(MediaType.APPLICATION_XML)
    public String xml(@QueryParam("section") @DefaultValue("directory") String section,
                      @FormParam("section") String formSection,
                      @FormParam("key_value") String keyValue) {
        String sec = formSection != null && !formSection.isBlank() ? formSection : section;
        sec = sec.replaceAll("[^A-Za-z0-9_-]", "");
        return switch (sec) {
            case "directory" -> directoryXml(keyValue);
            case "dialplan" -> dialplanXml();
            default -> notFound(sec);
        };
    }

    private static String directoryXml(String user) {
        List<SfExtension> all = SfExtension.listAll();
        if (all.isEmpty()) {
            return notFound("directory");
        }
        StringBuilder users = new StringBuilder();
        for (SfExtension e : all) {
            if (!e.enabled) {
                continue;
            }
            if (user != null && !user.isBlank() && !user.equals(e.extension)) {
                continue;
            }
            users.append("<user id=\"").append(esc(e.extension)).append("\">")
                    .append("<params>");
            String secret = e.passwordHash == null ? e.extension : e.passwordHash;
            if (et.elisa.security.SipDigestHasher.looksLikeA1Hash(secret)) {
                users.append("<param name=\"a1-hash\" value=\"").append(esc(secret)).append("\"/>");
            } else {
                users.append("<param name=\"password\" value=\"").append(esc(secret)).append("\"/>");
            }
            users.append("</params></user>");
        }
        if (users.isEmpty()) {
            return notFound("directory");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <document type="freeswitch/xml">
                  <section name="directory">
                    <domain name="freeswitch.lab">%s</domain>
                  </section>
                </document>
                """.formatted(users);
    }

    private static String dialplanXml() {
        List<SfDialplanRule> rules = SfDialplanRule.listAll();
        if (rules.isEmpty()) {
            return notFound("dialplan");
        }
        StringBuilder exts = new StringBuilder();
        for (SfDialplanRule r : rules) {
            if (!r.enabled) {
                continue;
            }
            exts.append("<extension name=\"").append(esc(r.name)).append("\">")
                    .append("<condition field=\"destination_number\" expression=\"")
                    .append(esc(r.destination)).append("\">")
                    // rtp_redirect: keep media on FS/TURN path through firewall (no bypass)
                    .append("<action application=\"set\" data=\"bypass_media=false\"/>")
                    .append("<action application=\"export\" data=\"nolocal:bypass_media=false\"/>")
                    .append("<action application=\"set\" data=\"elisa_rtp_redirect=true\"/>")
                    .append("<action application=\"bridge\" data=\"").append(esc(r.action)).append("\"/>")
                    .append("</condition></extension>");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <document type="freeswitch/xml">
                  <section name="dialplan">
                    <context name="default">%s</context>
                  </section>
                </document>
                """.formatted(exts);
    }

    private static String notFound(String section) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <document type="freeswitch/xml"><section name="%s"><result status="not found"/></section></document>
                """.formatted(section);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
