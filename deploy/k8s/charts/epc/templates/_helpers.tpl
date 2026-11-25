{{- define "epc.labels" -}}
app.kubernetes.io/part-of: epc
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* Gx peer list for the PCRF: one client link per PGW-C pod. */}}
{{- define "epc.pcrfPeers" -}}
{{- $realm := .Values.realm -}}
{{- $out := list -}}
{{- range $i, $_ := until (int .Values.nfs.pgwc.replicas) -}}
{{- $out = append $out (printf "epc-pgwc-%d.epc-pgwc-hl:3868:client:epc-pgwc-%d.%s:%s:gx" $i $i $realm $realm) -}}
{{- end -}}
{{- join ";" $out -}}
{{- end -}}

{{/* DNS names a pod waits for before the JVM starts (Diameter peers are resolved at start). */}}
{{- define "epc.waitFor" -}}
{{- $nf := .nf -}}
{{- $root := .root -}}
{{- $out := list -}}
{{- if $nf.gxServer -}}
{{- $out = append $out "epc-pcrf-0.epc-pcrf-hl" -}}
{{- end -}}
{{- if $nf.gxClient -}}
{{- range $i, $_ := until (int $root.Values.nfs.pgwc.replicas) -}}
{{- $out = append $out (printf "epc-pgwc-%d.epc-pgwc-hl" $i) -}}
{{- end -}}
{{- end -}}
{{- join " " $out -}}
{{- end -}}
