package et.elisa.ivr;

import et.elisa.persist.SfIvrEntry;
import et.elisa.persist.SfIvrMenu;
import java.util.List;

/** Builds FreeSWITCH dialplan XML for an IVR menu (xml_curl + optional conf export). */
public final class IvrXmlBuilder {

    private IvrXmlBuilder() {}

    public static String build(SfIvrMenu m, List<SfIvrEntry> entries) {
        if (m.xmlOverride != null && !m.xmlOverride.isBlank()) {
            return m.xmlOverride;
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\"?>\n<include>\n  <extension name=\"ivr-")
                .append(esc(m.name)).append("\">\n");
        xml.append("    <condition field=\"destination_number\" expression=\"^ivr-")
                .append(esc(m.name)).append("$\">\n");
        xml.append("      <action application=\"answer\"/>\n");
        if (m.greetingPrompt != null && !m.greetingPrompt.isBlank()) {
            xml.append("      <action application=\"playback\" data=\"")
                    .append(esc(m.greetingPrompt)).append("\"/>\n");
        }
        xml.append("      <action application=\"play_and_get_digits\" data=\"1 1 ")
                .append(Math.max(1, m.timeoutMs / 1000))
                .append(" 3 # $${hold_music} /invalid.wav ivr_digit \\d+\"/>\n");
        for (SfIvrEntry e : entries) {
            String digit = e.digit == null ? "" : e.digit.trim();
            String action = e.action == null ? "transfer" : e.action.trim();
            String dest = e.destination == null ? "" : e.destination.trim();
            if ("transfer".equalsIgnoreCase(action) && !dest.isBlank()) {
                xml.append("      <action application=\"transfer\" data=\"")
                        .append(esc(dest)).append(" XML default\"/>\n");
            } else if ("playback".equalsIgnoreCase(action) && !dest.isBlank()) {
                xml.append("      <action application=\"playback\" data=\"")
                        .append(esc(dest)).append("\"/>\n");
            } else {
                xml.append("      <!-- digit ").append(esc(digit))
                        .append(" -> ").append(esc(action))
                        .append(" ").append(esc(dest)).append(" -->\n");
            }
        }
        xml.append("    </condition>\n  </extension>\n</include>\n");
        return xml.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
