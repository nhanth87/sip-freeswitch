FROM eclipse-temurin:25-jre
WORKDIR /work
COPY target/quarkus-app/ /work/
ENV SF_HTML_DIR=/html
EXPOSE 8088 5060/udp 5060/tcp
ENTRYPOINT ["java","-Dsf.html-dir=${SF_HTML_DIR}","-jar","quarkus-run.jar"]
