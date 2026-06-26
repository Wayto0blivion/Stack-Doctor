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
        private val DATABASE_HINTS = listOf(
            "postgres", "postgresql", "pgvector", "timescale", "mysql", "mariadb", "percona",
            "mongo", "mssql", "sqlserver", "oracle", "cockroach", "cassandra", "scylla", "influx",
            "clickhouse", "couchdb", "rethinkdb", "neo4j", "surreal", "database", "db",
        )
        private val CACHE_HINTS = listOf("redis", "valkey", "keydb", "memcache", "dragonfly", "cache")
        private val PROXY_HINTS = listOf("nginx", "traefik", "caddy", "haproxy", "envoy", "httpd", "proxy", "gateway", "ingress")
        private val QUEUE_HINTS = listOf("rabbit", "kafka", "nats", "mqtt", "mosquitto", "queue", "broker", "zookeeper", "pulsar", "celery")
        private val WEB_HINTS = listOf("web", "frontend", "front", "ui", "app", "api", "backend", "server", "worker", "service")

        fun of(svc: ComposeService): ServiceCategory {
            // Match the name and the image separately so a generic image (or a built service with no
            // image) still gets categorised from a clearly-named service, and vice-versa.
            val name = svc.name.lowercase()
            val image = svc.image?.lowercase() ?: ""
            fun hit(hints: List<String>) = hints.any { it in name || it in image }
            return when {
                hit(PROXY_HINTS) -> PROXY
                hit(CACHE_HINTS) -> CACHE
                hit(DATABASE_HINTS) -> DATABASE
                hit(QUEUE_HINTS) -> QUEUE
                hit(WEB_HINTS) -> WEB
                else -> GENERIC
            }
        }
    }
}
