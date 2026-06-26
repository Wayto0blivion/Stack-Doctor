package com.qrowsolutions.stackdoctor.ui

import com.intellij.ui.JBColor
import com.qrowsolutions.stackdoctor.model.ComposeService

/**
 * A rough classification of a service from its name/image, used only for the graph's colour accent
 * and glyph. Best-effort and cosmetic — an unrecognised service is simply [GENERIC].
 */
enum class ServiceCategory(val glyph: String, val accent: JBColor) {
    DATABASE("DB", StackDoctorColors.ACCENT_DATABASE),
    CACHE("◆", StackDoctorColors.ACCENT_CACHE),
    PROXY("⇄", StackDoctorColors.ACCENT_PROXY),
    QUEUE("✉", StackDoctorColors.ACCENT_QUEUE),
    WEB("⬢", StackDoctorColors.ACCENT_WEB),
    GENERIC("●", StackDoctorColors.ACCENT_GENERIC);

    companion object {
        private val DATABASE_HINTS = listOf("postgres", "mysql", "mariadb", "mongo", "mssql", "cockroach", "db", "sqlserver", "oracle", "cassandra", "influx")
        private val CACHE_HINTS = listOf("redis", "memcache", "valkey", "cache")
        private val PROXY_HINTS = listOf("nginx", "traefik", "caddy", "haproxy", "envoy", "proxy", "gateway")
        private val QUEUE_HINTS = listOf("rabbit", "kafka", "nats", "mqtt", "queue", "broker", "zookeeper", "pulsar")
        private val WEB_HINTS = listOf("web", "frontend", "front", "ui", "app", "api", "backend", "server", "nginx-web")

        fun of(svc: ComposeService): ServiceCategory {
            val haystack = "${svc.name} ${svc.image ?: ""}".lowercase()
            return when {
                PROXY_HINTS.any { it in haystack } -> PROXY
                CACHE_HINTS.any { it in haystack } -> CACHE
                DATABASE_HINTS.any { it in haystack } -> DATABASE
                QUEUE_HINTS.any { it in haystack } -> QUEUE
                WEB_HINTS.any { it in haystack } -> WEB
                else -> GENERIC
            }
        }
    }
}
