# Elisa dist (sip-freeswitch)

PRIVATE — nhanth87/sip-freeswitch only. JDK 25 only.

```bash
./run.sh
# admin   http://127.0.0.1:8088/admin/
# metrics http://127.0.0.1:9090/metrics
# SIP     udp/tcp 5060 → FreeSWITCH trunk; ESL control :8021
```

Layout (fast-jar, OTA/ussdgw parity):
- `run.sh`            start (JDK 25 only)
- `quarkus-run.jar`   thin launcher — start via run.sh, never java -jar the app jar
- `elisa-app.jar`     application classes (APP_HOME root, not under app/)
- `lib/boot/ lib/main/` Quarkus bootstrap + deps (micro-jainslee, ra-freeswitch, …)
- `quarkus/`          generated app model
- `app/html/`         admin UI ONLY — edit HTML/CSS/JS without rebuild (no jars here)
- `configs/`          application.properties (external)
- `logs/`             elisa.log, gc / heap dumps

Deploy: `scp -r dist/elisa <host>:` then `./elisa/run.sh`.
