package et.elisa.fsxml;

import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfDialplanRule;
import et.elisa.persist.SfExtension;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** FreeSWITCH mod_xml_curl — IMS-first directory (P1), extension fallback. */
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
        StringBuilder users = new StringBuilder();
        List<ImsSubscriber> profiles = ImsSubscriber.listAll();
        for (ImsSubscriber s : profiles) {
            if (!s.enabled) {
                continue;
            }
            String uid = s.directoryUserId();
            if (user != null && !user.isBlank() && !user.equals(uid) && !user.equals(s.msisdn)) {
                continue;
            }
            users.append(userXml(uid, s.fsA1Hash, uid));
        }
        if (users.isEmpty()) {
            // Legacy fallback when no IMS profiles
            List<SfExtension> all = SfExtension.listAll();
            for (SfExtension e : all) {
                if (!e.enabled) {
                    continue;
                }
                if (user != null && !user.isBlank() && !user.equals(e.extension)) {
                    continue;
                }
                users.append(userXml(e.extension, e.passwordHash, e.extension));
            }
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

    private static String userXml(String id, String secretOrHash, String fallbackSecret) {
        String secret = secretOrHash == null || secretOrHash.isBlank() ? fallbackSecret : secretOrHash;
        StringBuilder sb = new StringBuilder();
        sb.append("<user id=\"").append(esc(id)).append("\"><params>");
        if (et.elisa.security.SipDigestHasher.looksLikeA1Hash(secret)) {
            sb.append("<param name=\"a1-hash\" value=\"").append(esc(secret)).append("\"/>");
        } else {
            sb.append("<param name=\"password\" value=\"").append(esc(secret)).append("\"/>");
        }
        sb.append("</params></user>");
        return sb.toString();
    }

    private static String dialplanXml() {
        List<SfDialplanRule> rules = SfDialplanRule.listAll();
        if (rules.isEmpty()) {
            // Lab MRF: ISC AS sip:1000@freeswitch answers so INVITE can confirm.
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <document type="freeswitch/xml">
                      <section name="dialplan">
                        <context name="public">
                          <extension name="elisa-mrf-1000">
                            <condition field="destination_number" expression="^(1000|alice)$">
                              <action application="set" data="bypass_media=false"/>
                              <action application="answer"/>
                              <action application="park"/>
                            </condition>
                          </extension>
                        </context>
                      </section>
                    </document>
                    """;
        }
        StringBuilder exts = new StringBuilder();
        for (SfDialplanRule r : rules) {
            if (!r.enabled) {
                continue;
            }
            exts.append("<extension name=\"").append(esc(r.name)).append("\">")
                    .append("<condition field=\"destination_number\" expression=\"")
                    .append(esc(r.destination)).append("\">")
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
                    <context name="public">%s</context>
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
