package et.elisa.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.ra.freeswitch.events.FsApiResponseEvent;
import com.microjainslee.ra.freeswitch.events.FsChannelEvent;
import com.microjainslee.ra.freeswitch.events.FsHeartbeatEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Consumes freeswitch-ra ESL events (DEBUG only — no body dumps). */
public final class FreeSwitchMonitorSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(FreeSwitchMonitorSbb.class);

    @Override
    public void sbbCreate() {
    }

    @Override
    public void sbbActivate() {
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case FsHeartbeatEvent e ->
                    LOG.debug("[FS] HEARTBEAT uptime={}", e.headers().getOrDefault("Up-Time", "?"));
            case FsChannelEvent e ->
                    LOG.debug("[FS] {} uuid={}", e.eventName(), e.uuid());
            case FsApiResponseEvent e ->
                    LOG.debug("[FS] api requestId={} ok={} live={} len={}",
                            e.requestId(), e.ok(), e.live(),
                            e.body() == null ? 0 : e.body().length());
            default -> {
            }
        }
    }
}
