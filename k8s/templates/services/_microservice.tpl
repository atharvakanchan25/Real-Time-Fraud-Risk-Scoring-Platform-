{{/*
Renders a Deployment, ClusterIP Service, and HPA for a microservice.

Usage:
  {{ include "fraud-platform.microservice" (dict
       "name"       "ingestion-service"
       "component"  "ingestion"
       "cfg"        .Values.services.ingestion
       "port"       .Values.services.ingestion.port
       "extraEnv"   (list)          <- optional extra env vars
       "root"       .
  ) }}
*/}}
{{- define "fraud-platform.microservice" -}}
{{- $cfg  := .cfg -}}
{{- $root := .root -}}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .name }}
  labels:
    {{- include "fraud-platform.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
spec:
  replicas: {{ $cfg.replicas }}
  selector:
    matchLabels:
      {{- include "fraud-platform.selectorLabels" (dict "component" .component "root" $root) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "fraud-platform.selectorLabels" (dict "component" .component "root" $root) | nindent 8 }}
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: "/actuator/prometheus"
        prometheus.io/port: {{ .port | quote }}
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: {{ .name }}
          image: {{ include "fraud-platform.image" (dict "name" .name "root" $root) }}
          imagePullPolicy: {{ $root.Values.global.imagePullPolicy }}
          ports:
            - containerPort: {{ .port }}
              name: http
          env:
            {{- include "fraud-platform.commonEnv" $root | nindent 12 }}
            - name: SERVER_PORT
              value: {{ .port | quote }}
            - name: SPRING_APPLICATION_NAME
              value: {{ .name | quote }}
            {{- if .extraEnv }}
            {{- toYaml .extraEnv | nindent 12 }}
            {{- end }}
          resources:
            {{- toYaml $cfg.resources | nindent 12 }}
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: {{ .port }}
            initialDelaySeconds: 20
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: {{ .port }}
            initialDelaySeconds: 40
            periodSeconds: 15
            failureThreshold: 3
---
apiVersion: v1
kind: Service
metadata:
  name: {{ .name }}
  labels:
    {{- include "fraud-platform.labels" $root | nindent 4 }}
    app.kubernetes.io/component: {{ .component }}
spec:
  selector:
    {{- include "fraud-platform.selectorLabels" (dict "component" .component "root" $root) | nindent 4 }}
  ports:
    - name: http
      port: {{ .port }}
      targetPort: {{ .port }}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ .name }}
  labels:
    {{- include "fraud-platform.labels" $root | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ .name }}
  minReplicas: {{ $cfg.hpa.minReplicas }}
  maxReplicas: {{ $cfg.hpa.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ $cfg.hpa.cpuUtilization }}
    {{- if $cfg.hpa.kafkaLagThreshold }}
    # Kafka consumer-lag metric via Prometheus Adapter
    # Requires: https://github.com/kubernetes-sigs/prometheus-adapter installed
    # and a custom metric rule mapping the PromQL below to
    # kafka_consumer_lag_<topic> external metric.
    - type: External
      external:
        metric:
          name: kafka_consumer_lag
          selector:
            matchLabels:
              topic: {{ $cfg.hpa.kafkaTopic | quote }}
              group: {{ $cfg.hpa.kafkaConsumerGroup | quote }}
        target:
          type: AverageValue
          averageValue: {{ $cfg.hpa.kafkaLagThreshold | quote }}
    {{- end }}
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30
      policies:
        - type: Pods
          value: 4
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Pods
          value: 1
          periodSeconds: 120
{{- end }}
