# EPC dist (sip-freeswitch/epc)

PRIVATE — nhanth87/sip-freeswitch. JDK 25. Control plane only — no Java GTP-U.

```bash
EPC_ROLE_HSS=true EPC_ROLE_SGWC=false EPC_ROLE_PGWC=false ./run.sh
# status http://127.0.0.1:8099/epc/status
```

`live` on /epc/status is peer evidence (CEA / GTP Echo / PFCP Association). LISTEN is not UP.
