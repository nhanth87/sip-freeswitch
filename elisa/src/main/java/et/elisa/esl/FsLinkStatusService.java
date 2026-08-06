package et.elisa.esl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class FsLinkStatusService {
    @Inject EslClient esl;
    public Map<String, Object> status() {
        String sofia = esl.api("sofia status");
        String channels = esl.api("show channels count");
        return Map.of("eslLive", esl.live(), "sofia", sofia, "channels", extractCount(channels));
    }
    private static long extractCount(String output) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(\\d+)").matcher(output);
        return match.find() ? Long.parseLong(match.group(1)) : 0L;
    }
}
