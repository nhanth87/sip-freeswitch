# EPC host — one image, HSS / SGW-C / PGW-C via EPC_ROLE_* flags. JDK 25.
# NOTE: JDK base, not JRE — ra-diameter's Corsac DiameterParser registers AVPs
# through javax.tools.JavaCompiler at runtime; a JRE image NPEs in
# loadClassesWithCompiler and the Diameter RA never activates (2026-08-23 wire).
# Prefers a pre-built tree: (cd epc && mvn -pl epc-host -am package)
FROM eclipse-temurin:25-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
COPY epc-host/target/quarkus-app/ /work/

EXPOSE 8099 3868 2123/udp 8805/udp

HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=8 \
  CMD curl -fsS http://127.0.0.1:8099/epc/status || exit 1

ENTRYPOINT ["java", "-jar", "/work/quarkus-run.jar"]
