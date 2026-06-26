package com.qrowsolutions.stackdoctor.analysis

import com.qrowsolutions.stackdoctor.model.ComposeService

/**
 * Produces a context-aware `healthcheck:` for a service, inferring an appropriate probe from the
 * image/name (and, for HTTP/TCP services, a representative port). Pure and unit-testable — the file
 * edit that writes it back lives in `inspection.HealthcheckWriter`.
 */
object HealthcheckGenerator {

    data class Healthcheck(
        /** The `test:` value, already in YAML flow-sequence form, e.g. `["CMD", "redis-cli", "ping"]`. */
        val test: String,
        val interval: String = "10s",
        val timeout: String = "5s",
        val retries: Int = 5,
        val startPeriod: String = "30s",
        /** One-line note on what the probe does, shown when confirming the change. */
        val rationale: String,
    )

    /** A suggested healthcheck for [svc], or null when nothing meaningful can be inferred. */
    fun generate(svc: ComposeService): Healthcheck? {
        val hay = "${svc.name} ${svc.image ?: ""}".lowercase()
        fun has(vararg keys: String) = keys.any { it in hay }

        when {
            has("postgres", "postgis", "timescale", "cockroach") ->
                return exec("Waits for Postgres to accept connections", "pg_isready", "-U", "postgres")
            has("mysql", "mariadb", "percona") ->
                return exec("Pings the MySQL/MariaDB server", "mysqladmin", "ping", "-h", "127.0.0.1")
            has("mongo") ->
                return shell(
                    "Runs a MongoDB ping admin command",
                    "mongosh --quiet --eval \"db.adminCommand('ping')\" || mongo --quiet --eval \"db.adminCommand('ping')\"",
                )
            has("redis", "valkey", "keydb") ->
                return exec("Sends a Redis PING", "redis-cli", "ping")
            has("memcached") ->
                return shell("Reads memcached stats over the socket", "echo stats | nc -w 1 127.0.0.1 11211")
            has("rabbitmq") ->
                return exec("Pings the RabbitMQ node", "rabbitmq-diagnostics", "-q", "ping")
            has("elasticsearch", "opensearch") ->
                return httpCheck("Polls the cluster health endpoint", "9200", "/_cluster/health")
            has("zookeeper") ->
                return shell("Checks ZooKeeper replies 'imok'", "echo ruok | nc -w 1 127.0.0.1 2181 | grep -q imok")
        }

        val port = primaryPort(svc)
        // Anything HTTP-ish that exposes a port gets an HTTP probe.
        if (port != null && has(
                "nginx", "traefik", "caddy", "haproxy", "envoy", "httpd", "apache", "tomcat",
                "web", "frontend", "front", "ui", "api", "backend", "server", "gateway", "app",
            )
        ) {
            return httpCheck("Requests the service root over HTTP", port, "/")
        }
        // Otherwise, if we at least know a port, fall back to a plain TCP connect.
        if (port != null) return tcp("Opens a TCP connection to the service port", port)
        return null
    }

    /** A representative container port: first published/declared port, else first `expose` entry. */
    private fun primaryPort(svc: ComposeService): String? {
        svc.ports.firstOrNull { it.containerPort != null }?.containerPort?.substringBefore('/')?.let { return it }
        return svc.expose.firstOrNull()?.substringBefore('/')
    }

    private fun exec(rationale: String, vararg args: String): Healthcheck =
        Healthcheck(test = args.joinToString(", ", "[\"CMD\", ", "]") { "\"$it\"" }, rationale = rationale)

    private fun shell(rationale: String, command: String): Healthcheck =
        Healthcheck(test = "[\"CMD-SHELL\", \"${command.replace("\"", "\\\"")}\"]", rationale = rationale)

    private fun httpCheck(rationale: String, port: String, path: String): Healthcheck =
        shell(rationale, "curl -fsS http://localhost:$port$path || wget -qO- http://localhost:$port$path || exit 1")

    private fun tcp(rationale: String, port: String): Healthcheck =
        shell(rationale, "nc -z 127.0.0.1 $port || exit 1")
}
