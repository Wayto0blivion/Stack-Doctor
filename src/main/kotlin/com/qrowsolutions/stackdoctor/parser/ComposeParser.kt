package com.qrowsolutions.stackdoctor.parser

import com.qrowsolutions.stackdoctor.model.ComposeProject
import com.qrowsolutions.stackdoctor.model.ComposeService
import com.qrowsolutions.stackdoctor.model.PortMapping
import com.qrowsolutions.stackdoctor.model.VolumeMount
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Parses a [YAMLFile] that is a docker-compose file into the IDE-agnostic [ComposeProject] model.
 *
 * Tolerant by design: unknown keys are ignored and both the short and long syntaxes for
 * `ports`, `volumes`, `depends_on`, `environment` and `env_file` are accepted, because real-world
 * compose files mix them freely.
 */
object ComposeParser {

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    fun parse(file: YAMLFile): ComposeProject? {
        val root = topMapping(file) ?: return null

        val servicesMapping = root.childMapping("services")
        val declaredNetworks = root.childMapping("networks")?.keyTexts() ?: emptySet()
        val declaredVolumes = root.childMapping("volumes")?.keyTexts() ?: emptySet()

        val services = servicesMapping?.keyValues
            ?.mapNotNull { kv -> parseService(kv) }
            ?: emptyList()

        return ComposeProject(
            filePath = file.virtualFile?.path ?: file.name,
            fileName = file.name,
            services = services,
            declaredNetworks = declaredNetworks,
            declaredVolumes = declaredVolumes,
        )
    }

    private fun parseService(kv: YAMLKeyValue): ComposeService? {
        val name = kv.keyText.ifBlank { return null }
        val body = kv.value as? YAMLMapping ?: return ComposeService(
            name = name, image = null, hasBuild = false, dependsOn = emptyList(), links = emptyList(),
            ports = emptyList(), expose = emptyList(), volumes = emptyList(), networks = emptySet(),
            hasHealthcheck = false, healthcheckDisabled = false, envFiles = emptyList(),
            environmentKeys = emptySet(), restart = null,
        )

        val healthcheck = body.childMapping("healthcheck")
        return ComposeService(
            name = name,
            image = body.scalarText("image"),
            hasBuild = body.getKeyValueByKey("build") != null,
            dependsOn = parseDependsOn(body),
            links = parseLinks(body),
            ports = parsePorts(body),
            expose = body.stringList("expose"),
            volumes = parseVolumes(body),
            networks = parseNetworks(body),
            hasHealthcheck = healthcheck != null,
            healthcheckDisabled = healthcheck?.scalarText("disable")?.equals("true", ignoreCase = true) == true,
            envFiles = parseEnvFile(body),
            environmentKeys = parseEnvironmentKeys(body),
            restart = body.scalarText("restart"),
        )
    }

    /** `depends_on` is either a list of names or a map of name -> { condition: ... }. */
    private fun parseDependsOn(service: YAMLMapping): List<String> {
        val value = service.getKeyValueByKey("depends_on")?.value ?: return emptyList()
        return when (value) {
            is YAMLSequence -> value.scalarItems()
            is YAMLMapping -> value.keyTexts().toList()
            else -> emptyList()
        }
    }

    private fun parseLinks(service: YAMLMapping): List<String> =
        service.stringList("links").map { it.substringBefore(':') }

    private fun parsePorts(service: YAMLMapping): List<PortMapping> {
        val seq = service.getKeyValueByKey("ports")?.value as? YAMLSequence ?: return emptyList()
        return seq.items.mapNotNull { item ->
            when (val v = item.value) {
                is YAMLMapping -> parseLongPort(v)
                is YAMLScalar -> parseShortPort(v.textValue)
                else -> null
            }
        }
    }

    private fun parseLongPort(m: YAMLMapping): PortMapping {
        val target = m.scalarText("target")
        val published = m.scalarText("published")
        val hostIp = m.scalarText("host_ip")
        val protocol = m.scalarText("protocol") ?: "tcp"
        val raw = buildString {
            hostIp?.let { append(it).append(':') }
            published?.let { append(it).append(':') }
            append(target ?: "")
        }
        return PortMapping(raw, hostIp, published, target, protocol)
    }

    /** Short syntax: `[HOST_IP:][HOST:]CONTAINER[/PROTOCOL]`. */
    private fun parseShortPort(raw: String): PortMapping {
        val (body, protocol) = raw.split('/', limit = 2).let {
            it[0] to (it.getOrNull(1) ?: "tcp")
        }
        val parts = body.split(':')
        return when (parts.size) {
            1 -> PortMapping(raw, null, null, parts[0], protocol)
            2 -> PortMapping(raw, null, parts[0], parts[1], protocol)
            else -> {
                // ip:host:container — IPv6 host_ip may itself contain colons, so take last two as ports.
                val container = parts[parts.size - 1]
                val host = parts[parts.size - 2]
                val ip = parts.subList(0, parts.size - 2).joinToString(":")
                PortMapping(raw, ip.ifBlank { null }, host.ifBlank { null }, container, protocol)
            }
        }
    }

    private fun parseVolumes(service: YAMLMapping): List<VolumeMount> {
        val seq = service.getKeyValueByKey("volumes")?.value as? YAMLSequence ?: return emptyList()
        return seq.items.mapNotNull { item ->
            when (val v = item.value) {
                is YAMLMapping -> parseLongVolume(v)
                is YAMLScalar -> parseShortVolume(v.textValue)
                else -> null
            }
        }
    }

    private fun parseLongVolume(m: YAMLMapping): VolumeMount {
        val type = m.scalarText("type")
        val source = m.scalarText("source")
        val target = m.scalarText("target")
        val isBind = type == "bind" || (source != null && looksLikePath(source))
        val isNamed = type == "volume" || (source != null && !isBind)
        return VolumeMount("${source ?: ""}:${target ?: ""}", source, target, isNamed, isBind)
    }

    private fun parseShortVolume(raw: String): VolumeMount {
        // Preserve a leading Windows drive (C:\...) so we don't split it as host:container.
        val driveMatch = WINDOWS_DRIVE.find(raw)
        val rest = if (driveMatch != null) raw.substring(2) else raw
        val restParts = rest.split(':')
        val parts = if (driveMatch != null) {
            listOf(raw.substring(0, 2) + restParts[0]) + restParts.drop(1)
        } else {
            restParts
        }

        if (parts.size == 1) {
            // Anonymous volume: only a container path is given.
            return VolumeMount(raw, null, parts[0], isNamedVolume = false, isBindMount = false)
        }
        val source = parts[0]
        val target = parts[1]
        val isBind = looksLikePath(source)
        return VolumeMount(raw, source, target, isNamedVolume = !isBind, isBindMount = isBind)
    }

    private fun looksLikePath(source: String): Boolean =
        source.startsWith("./") || source.startsWith("../") || source.startsWith("/") ||
            source.startsWith("~") || source.startsWith(".") || WINDOWS_DRIVE.containsMatchIn(source)

    /** `networks` is a list of names or a map of name -> { aliases: ... }. */
    private fun parseNetworks(service: YAMLMapping): Set<String> {
        val value = service.getKeyValueByKey("networks")?.value ?: return emptySet()
        return when (value) {
            is YAMLSequence -> value.scalarItems().toSet()
            is YAMLMapping -> value.keyTexts()
            else -> emptySet()
        }
    }

    /** `env_file` is a single path, a list of paths, or a list of { path, required } maps. */
    private fun parseEnvFile(service: YAMLMapping): List<String> {
        val value = service.getKeyValueByKey("env_file")?.value ?: return emptyList()
        return when (value) {
            is YAMLScalar -> listOf(value.textValue)
            is YAMLSequence -> value.items.mapNotNull { item ->
                when (val v = item.value) {
                    is YAMLScalar -> v.textValue
                    is YAMLMapping -> v.scalarText("path")
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    /** `environment` is a `KEY=value` list or a `KEY: value` map. We only need the keys. */
    private fun parseEnvironmentKeys(service: YAMLMapping): Set<String> {
        val value = service.getKeyValueByKey("environment")?.value ?: return emptySet()
        return when (value) {
            is YAMLSequence -> value.scalarItems().map { it.substringBefore('=').trim() }.toSet()
            is YAMLMapping -> value.keyTexts()
            else -> emptySet()
        }
    }

    // ---- YAML PSI helpers -------------------------------------------------------------------

    private fun topMapping(file: YAMLFile): YAMLMapping? =
        file.documents.firstNotNullOfOrNull { it.topLevelValue as? YAMLMapping }

    private fun YAMLMapping.childMapping(key: String): YAMLMapping? =
        getKeyValueByKey(key)?.value as? YAMLMapping

    private fun YAMLMapping.scalarText(key: String): String? =
        (getKeyValueByKey(key)?.value as? YAMLScalar)?.textValue?.takeIf { it.isNotBlank() }

    private fun YAMLMapping.keyTexts(): Set<String> =
        keyValues.mapNotNullTo(LinkedHashSet()) { it.keyText.ifBlank { null } }

    /** Reads a key whose value may be either a scalar or a sequence of scalars, returning a list. */
    private fun YAMLMapping.stringList(key: String): List<String> {
        return when (val v = getKeyValueByKey(key)?.value) {
            is YAMLScalar -> listOf(v.textValue)
            is YAMLSequence -> v.scalarItems()
            else -> emptyList()
        }
    }

    private fun YAMLSequence.scalarItems(): List<String> =
        items.mapNotNull { (it.value as? YAMLScalar)?.textValue?.takeIf { t -> t.isNotBlank() } }

    @Suppress("unused")
    private fun YAMLValue.asMapping(): YAMLMapping? = this as? YAMLMapping
}
