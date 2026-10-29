package et.elisa.fsxml;

import et.elisa.ims.hss.HssLiteService;
import et.elisa.persist.SfDialplanRule;
import et.elisa.persist.SfExtension;
import et.elisa.profile.ElisaProfileService;
import et.elisa.profile.ImsSubscriberProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** FreeSWITCH mod_xml_curl — IMS-first directory (P1), extension fallback. */
@Path("/fs/xml")
public class FsXmlCurlResource {

    @Inject HssLiteService hss;
    @Inject ElisaProfileService sleeProfiles;

    @ConfigProperty(name = "sf.ims.realm", defaultValue = "ims.lab")
    String realm;

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
            case "dialplan" -> cachedDialplanXml();
            default -> notFound(sec);
        };
    }

    private String directoryXml(String user) {
        if (user == null || user.isBlank()) {
            // 10k/server: never dump the directory. FS xml_curl per REGISTER/INVITE sends user=.
            return notFound("directory");
        }
        String key = user.trim();
        StringBuilder users = new StringBuilder();
        if (sleeProfiles != null) {
            sleeProfiles.getSubscriber(key)
                    .or(() -> sleeProfiles.findByDirectoryUserId(key))
                    .ifPresent(p -> appendProfileUser(users, p));
        }
        if (users.isEmpty() && hss != null) {
            hss.resolveIdentity(key).ifPresent(s -> {
                if (s.enabled) {
                    String uid = s.directoryUserId();
                    users.append(userXml(uid, s.fsA1Hash, uid));
                }
            });
        }
        if (users.isEmpty()) {
            SfExtension e = SfExtension.find("extension", key).firstResult();
            if (e != null && e.enabled) {
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
                    <domain name="%s">%s</domain>
                  </section>
                </document>
                """.formatted(esc(realm == null || realm.isBlank() ? "ims.lab" : realm), users);
    }

    private static void appendProfileUser(StringBuilder users, ImsSubscriberProfile p) {
        if (p == null || "false".equalsIgnoreCase(p.getEnabled())) {
            return;
        }
        String uid = p.getDirectoryUserId();
        if (uid == null || uid.isBlank()) {
            uid = p.getMsisdn();
        }
        if (uid == null || uid.isBlank()) {
            return;
        }
        users.append(userXml(uid, p.getFsA1Hash(), uid));
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

    /** FS fetches the dialplan once per call; rules change on admin edits only. */
    private static final long DIALPLAN_TTL_NANOS = 2_000_000_000L;
    private record DialplanSnapshot(String xml, long expiresAtNanos) {}
    private static volatile DialplanSnapshot dialplanCache;

    private String cachedDialplanXml() {
        DialplanSnapshot snap = dialplanCache;
        long now = System.nanoTime();
        if (snap != null && now - snap.expiresAtNanos() < 0) {
            return snap.xml();
        }
        String xml = dialplanXml();
        dialplanCache = new DialplanSnapshot(xml, now + DIALPLAN_TTL_NANOS);
        return xml;
    }

    private static String dialplanXml() {
        List<SfDialplanRule> rules = SfDialplanRule.listAll();
        if (rules.isEmpty()) {
            // Lab MRF: ISC AS sip:1000@freeswitch answers and plays tone so RTP
            // actually traverses FreeSWITCH (park keeps the channel but sends no audio).
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <document type="freeswitch/xml">
                      <section name="dialplan">
                        <context name="public">
                          <extension name="elisa-mrf-1000">
                            <condition field="destination_number" expression="^1000$">
                              <action application="set" data="absolute_codec_string=OPUS,G722,PCMA,PCMU"/>
                              <action application="set" data="bypass_media=false"/>
                              <action application="answer"/>
                              <action application="playback" data="tone_stream://%(1000,0,350,440);loops=-1"/>
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
                    .append("<action application=\"set\" data=\"absolute_codec_string=OPUS,G722,PCMA,PCMU\"/>")
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
