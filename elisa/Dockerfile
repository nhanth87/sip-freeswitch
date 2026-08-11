# Elisa Quarkus fast-jar runtime (JDK 25).
#
# Prefers a pre-built tree (SNAPSHOT micro-jainslee lives in host ~/.m2):
#   ./tools/build-all.sh
#   docker compose build elisa
#
# Layout must match Quarkus fast-jar: quarkus-run.jar + app/ + lib/ + quarkus/
FROM eclipse-temurin:25-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

COPY target/quarkus-app/ /work/
COPY docker-entrypoint.sh /docker-entrypoint-elisa.sh
RUN chmod +x /docker-entrypoint-elisa.sh

ENV SF_HTML_DIR=/html
# Soft-fail ESL is app-side (eslLive=false); do not block container start on FS.

EXPOSE 8088 5060/udp 5060/tcp

HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=8 \
  CMD curl -fsS http://127.0.0.1:8088/q/health || exit 1

# Quarkus maps SF_HTML_DIR via application.properties — avoid exec-form ${VAR} (not expanded).
ENTRYPOINT ["/docker-entrypoint-elisa.sh"]
CMD ["java", "-jar", "/work/quarkus-run.jar"]
