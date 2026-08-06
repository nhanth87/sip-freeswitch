package et.sipfreeswitch.admin;

import et.sipfreeswitch.metrics.SipCpsMetrics;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/admin")
public class AdminHtmxResource {

    @Inject
    SipCpsMetrics metrics;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <title>sip-freeswitch</title>
                  <link rel="stylesheet" href="/admin/admin.css"/>
                  <script src="https://unpkg.com/htmx.org@2.0.4"></script>
                </head>
                <body class="bay">
                  <header><h1>sip-freeswitch</h1><p>SIP edge · FreeSWITCH trunk · 10k INVITE/s target</p></header>
                  <section id="status" hx-get="/admin/partials/status" hx-trigger="load, every 2s" hx-swap="innerHTML">
                    loading…
                  </section>
                </body>
                </html>
                """;
    }

    @GET
    @Path("/partials/status")
    @Produces(MediaType.TEXT_HTML)
    public String statusPartial() {
        return """
                <dl class="metrics">
                  <dt>INVITE total</dt><dd>%d</dd>
                  <dt>Rejects</dt><dd>%d</dd>
                  <dt>Dialogs active</dt><dd>%d</dd>
                </dl>
                <p class="hint">Prometheus RA :9090 · capacity claim only after SIPp green</p>
                """.formatted(metrics.inviteCount(), metrics.rejectCount(), metrics.activeDialogs());
    }
}
