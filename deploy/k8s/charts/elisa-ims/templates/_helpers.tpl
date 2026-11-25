{{- define "elisa.labels" -}}
app.kubernetes.io/part-of: elisa-ims
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "elisa.image" -}}
"{{ .Values.image.repository }}:{{ .Values.image.tag }}"
{{- end -}}

{{/*
Env shared by every Elisa role. Args: root, name (StatefulSet / headless Service), nf (values block),
dist (bool: join elisa-cluster).
*/}}
{{- define "elisa.env" -}}
{{- $root := .root -}}
{{- $nf := .nf -}}
- name: POD_NAME
  valueFrom: {fieldRef: {fieldPath: metadata.name}}
- name: POD_NAMESPACE
  valueFrom: {fieldRef: {fieldPath: metadata.namespace}}
- {name: JAVA_TOOL_OPTIONS, value: "{{ $root.Values.javaOpts }} -Djgroups.dns.query={{ .name }}-hl.$(POD_NAMESPACE).svc.cluster.local"}
- {name: SF_JDBC_URL, value: "jdbc:postgresql://elisa-postgres:5432/elisa"}
- {name: SF_DB_USER, value: elisa}
- name: SF_DB_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $root.Values.dbSecret }}, key: password}}
- {name: SF_HTML_DIR, value: /html}
- {name: SF_FS_CONF_DIR, value: /html}
- {name: SF_ADMIN_DEV_LOGIN, value: "{{ $root.Values.adminDevLogin }}"}
- name: SF_ADMIN_API_KEY
  valueFrom: {secretKeyRef: {name: {{ $root.Values.labSecret }}, key: admin-api-key}}
- name: SF_ADMIN_SESSION_HMAC_SECRET
  valueFrom: {secretKeyRef: {name: {{ $root.Values.labSecret }}, key: admin-hmac-secret}}
- name: SF_ADMIN_BOOTSTRAP_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $root.Values.labSecret }}, key: admin-bootstrap-password}}
- {name: SF_IMS_BGCF_URI, value: "sip:unused-bgcf.lab:5060"}
- {name: SF_IMS_ENABLED, value: "true"}
- {name: SF_IMS_PCSCF_URI, value: "sip:pcscf.{{ $root.Values.realm }}:5060"}
- {name: SF_IMS_ICSCF_URI, value: "sip:icscf:5060"}
{{- range $r := list "PCSCF" "ICSCF" "SCSCF" "HSS" }}
- {name: SF_IMS_{{ $r }}, value: {{ index $nf.env (printf "SF_IMS_%s" $r) | default "false" | quote }}}
{{- end }}
- {name: SF_CLUSTER_NODE_ID, value: "elisa-$(POD_NAME)"}
{{- if .dist }}
- {name: SF_CLUSTER_ISPN_MODE, value: DIST_SYNC}
- {name: SF_CLUSTER_ISPN_NAME, value: elisa-cluster}
- {name: SF_CLUSTER_INFINISPAN_JGROUPS_STACK, value: kubernetes}
{{- else }}
- {name: SF_CLUSTER_ISPN_MODE, value: LOCAL}
{{- end }}
{{- range $k, $v := $nf.env }}
{{- if not (has $k (list "SF_IMS_PCSCF" "SF_IMS_ICSCF" "SF_IMS_SCSCF" "SF_IMS_HSS")) }}
- {name: {{ $k }}, value: {{ $v | quote }}}
{{- end }}
{{- end }}
{{- end -}}

{{/* ICE / TURN / rtp_redirect (docs/agents/media-ice-turn.md) — P-CSCF and S-CSCF. */}}
{{- define "elisa.mediaEnv" -}}
- {name: SF_MEDIA_RTP_REDIRECT, value: "true"}
- {name: SF_MEDIA_REQUIRE_TURN, value: "true"}
- {name: SF_MEDIA_ICE_REQUIRED, value: "true"}
- {name: SF_MEDIA_TURN_URI, value: "turn:coturn:3478"}
- name: SF_MEDIA_TURN_USERNAME
  valueFrom: {secretKeyRef: {name: {{ .Values.labSecret }}, key: turn-username}}
- name: SF_MEDIA_TURN_CREDENTIAL
  valueFrom: {secretKeyRef: {name: {{ .Values.labSecret }}, key: turn-password}}
- {name: SF_MEDIA_STUN_URI, value: "stun:coturn:3478"}
- {name: SIP_RA_ICE_ENABLED, value: "true"}
- {name: SIP_RA_STUN_SERVER, value: coturn}
- {name: SIP_RA_STUN_PORT, value: "3478"}
{{- end -}}

{{/* Wait for Postgres, then for the DNS names given (Diameter peers are resolved at start). */}}
{{- define "elisa.waitDeps" -}}
- name: wait-deps
  image: {{ include "elisa.image" .root }}
  imagePullPolicy: {{ .root.Values.image.pullPolicy }}
  command:
    - bash
    - -c
    - |
      until (echo > /dev/tcp/elisa-postgres/5432) 2>/dev/null; do echo "waiting for elisa-postgres"; sleep 2; done
      for h in {{ .names }}; do
        until getent hosts "$h" >/dev/null; do echo "waiting for $h"; sleep 2; done
      done
{{- end -}}

{{- define "elisa.probes" -}}
startupProbe:
  httpGet: {path: /q/health, port: http}
  periodSeconds: 5
  failureThreshold: 48
readinessProbe:
  httpGet: {path: /q/health, port: http}
  periodSeconds: 10
livenessProbe:
  httpGet: {path: /q/health, port: http}
  periodSeconds: 20
  failureThreshold: 6
{{- end -}}
