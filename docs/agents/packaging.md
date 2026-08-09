# Packaging

**JDK: Java 25 only** (mise `zulu-25`).

**Prove the artifact (NON-NEGOTIABLE — SIẾT):** Green `mvn test` never means the host runs new Elisa code. After `./tools/package-dist.sh` + ship/restart, prove jar/html **mtime** or `jar tf … | grep <NewClass>`, running PID classpath, and the live admin/SIP surface that failed — before claiming fixed/redeployed. Never sandbox `package-dist` / `mvn package|test` / `build-all` — Shell **`required_permissions: ["all"]`** first. → root [AGENTS.md](../../AGENTS.md) · [lessons.md](lessons.md).

```bash
./tools/build-all.sh
./tools/package-dist.sh
# ship dist/elisa/ only
```

Layout:

```
dist/elisa/
  run.sh
  bin/                 # quarkus-app
  app/html/            # editable UI
  configs/application.properties
```

Docker lab:

```bash
docker compose up -d --build
```

Services: `postgres`, `freeswitch`, `elisa` on `sf-net`.
