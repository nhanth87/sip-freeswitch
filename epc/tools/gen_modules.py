#!/usr/bin/env python3
"""Generate remaining epc/ Java modules. Idempotent overwrite of generated sources."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def w(rel: str, content: str) -> None:
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    text = content.strip() + "\n"
    if text.startswith("<?xml"):
        text = content.lstrip()
        if not text.endswith("\n"):
            text += "\n"
    p.write_text(text, encoding="utf-8")
    print("W", rel)


def pom(artifact: str, extra_deps: str, extra_name: str | None = None) -> None:
    name = extra_name or f"epc :: {artifact}"
    w(
        f"{artifact}/pom.xml",
        f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>et.epc</groupId>
    <artifactId>epc-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>{artifact}</artifactId>
  <name>{name}</name>
  <dependencies>
{extra_deps}
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-api</artifactId>
    </dependency>
  </dependencies>
</project>
''',
    )


API = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-api</artifactId>
    </dependency>"""
CRYPTO = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-crypto</artifactId>
    </dependency>"""
HSS = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-hss</artifactId>
    </dependency>"""
GTP = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>ra-gtpv2c</artifactId>
    </dependency>"""
PFCP = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>ra-pfcp</artifactId>
    </dependency>"""
JAPI = """    <dependency>
      <groupId>com.microjainslee</groupId>
      <artifactId>jainslee-api</artifactId>
    </dependency>"""
PCEF = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-pcef</artifactId>
    </dependency>"""
SGWC = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-sgwc</artifactId>
    </dependency>"""
PGWC = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-pgwc</artifactId>
    </dependency>"""
UPL = """    <dependency>
      <groupId>et.epc</groupId>
      <artifactId>epc-up-linux</artifactId>
    </dependency>"""


def main() -> None:
    pom("epc-hss", API + "\n" + CRYPTO)
    pom("ra-gtpv2c", API + "\n" + JAPI)
    pom("ra-pfcp", API + "\n" + JAPI)
    pom("epc-pcef", API)
    pom("epc-sgwc", API + "\n" + GTP + "\n" + PFCP + "\n" + JAPI)
    pom("epc-pgwc", API + "\n" + GTP + "\n" + PFCP + "\n" + PCEF + "\n" + JAPI)
    pom("epc-up-linux", API + "\n" + PFCP)
    print("poms ok")


if __name__ == "__main__":
    main()
