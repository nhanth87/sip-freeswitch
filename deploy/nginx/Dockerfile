# Edge nginx — stream SIP + http admin (docs/agents/nginx-ingress.md)
FROM nginx:1.27-alpine

RUN apk add --no-cache openssl \
    && mkdir -p /etc/nginx/certs \
    && nginx -V 2>&1 | grep -q -- --with-stream

COPY nginx.conf /etc/nginx/nginx.conf
COPY docker-entrypoint.sh /docker-entrypoint-elisa.sh
RUN chmod +x /docker-entrypoint-elisa.sh

EXPOSE 8080 8443 5060/tcp 5060/udp

ENTRYPOINT ["/docker-entrypoint-elisa.sh"]
CMD ["nginx", "-g", "daemon off;"]
