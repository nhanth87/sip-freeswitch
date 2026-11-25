# Elisa runtime for Kubernetes: the compose image + the admin UI (compose mounts ./app/html instead).
#   docker build -f deploy/k8s/images/elisa.Dockerfile --build-arg BASE=sip-freeswitch/elisa-base:lab \
#     -t sip-freeswitch/elisa:lab .
ARG BASE=sip-freeswitch/elisa-base:lab
FROM ${BASE}
COPY app/html /html
