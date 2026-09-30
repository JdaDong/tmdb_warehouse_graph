{{/*
命名与标签的通用模板。
所有资源统一打上 app.kubernetes.io/* 标签，便于 kubectl 按应用整体筛选，
也便于 Prometheus 的服务发现按 label 匹配。
*/}}

{{- define "tmdbwh.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "tmdbwh.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s" (include "tmdbwh.name" .) -}}
{{- end -}}
{{- end -}}

{{- define "tmdbwh.labels" -}}
app.kubernetes.io/name: {{ include "tmdbwh.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "tmdbwh.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tmdbwh.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "tmdbwh.componentLabels" -}}
{{ include "tmdbwh.labels" . }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "tmdbwh.componentSelectorLabels" -}}
{{ include "tmdbwh.selectorLabels" .ctx }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
对象存储地址：优先使用外部 S3，否则用内置的 MinIO 服务名。
这样切换存储后端只需要改 values，不需要改任何组件的连接串。
*/}}
{{- define "tmdbwh.s3Endpoint" -}}
{{- if .Values.externalS3.enabled -}}
{{- .Values.externalS3.endpoint -}}
{{- else -}}
http://{{ include "tmdbwh.fullname" . }}-minio:9000
{{- end -}}
{{- end -}}

{{- define "tmdbwh.s3Bucket" -}}
{{- if .Values.externalS3.enabled -}}
{{- .Values.externalS3.bucket -}}
{{- else -}}
tmdb-lake
{{- end -}}
{{- end -}}
