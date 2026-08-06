# Packaging

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
