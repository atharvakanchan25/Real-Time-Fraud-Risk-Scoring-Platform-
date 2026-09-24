{{/*
Expand the name of the chart.
*/}}
{{- define "fraud-platform.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource.
*/}}
{{- define "fraud-platform.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Selector labels for a given service component.
Usage: include "fraud-platform.selectorLabels" (dict "component" "scoring" "root" .)
*/}}
{{- define "fraud-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ .root.Chart.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
Full image reference.
Usage: include "fraud-platform.image" (dict "name" "scoring-service" "root" .)
*/}}
{{- define "fraud-platform.image" -}}
{{ .root.Values.global.imageRegistry }}{{ .name }}:{{ .root.Values.global.imageTag }}
{{- end }}

{{/*
Standard environment variables injected into every service pod.
*/}}
{{- define "fraud-platform.commonEnv" -}}
- name: SPRING_KAFKA_BOOTSTRAP_SERVERS
  valueFrom:
    secretKeyRef:
      name: fraud-platform-secrets
      key: kafka-bootstrap-servers
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: fraud-platform-secrets
      key: postgres-username
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: fraud-platform-secrets
      key: postgres-password
- name: SPRING_DATA_REDIS_HOST
  valueFrom:
    secretKeyRef:
      name: fraud-platform-secrets
      key: redis-host
- name: SPRING_DATA_REDIS_PORT
  valueFrom:
    secretKeyRef:
      name: fraud-platform-secrets
      key: redis-port
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ .Values.otel.endpoint | quote }}
- name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
  value: "1.0"
{{- end }}
