package com.qrowsolutions.stackdoctor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.analysis.ComposeMerge
import com.qrowsolutions.stackdoctor.analysis.MergeResult
import com.qrowsolutions.stackdoctor.model.ComposeProject
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import org.jetbrains.yaml.psi.YAMLFile

class ComposeMergeTest : BasePlatformTestCase() {

    private fun parse(name: String, text: String): ComposeProject =
        ComposeParser.parse(myFixture.configureByText(name, text) as YAMLFile)!!

    /** Parses a base + overlay (base first, so its model is captured before the overlay is opened). */
    private fun merge(base: String, overlay: String): MergeResult {
        val baseProject = parse("docker-compose.yml", base)
        val overlayProject = parse("docker-compose.override.yml", overlay)
        return ComposeMerge.merge(baseProject, listOf(overlayProject))
    }

    // ---- override file-name detection -------------------------------------------------------

    fun testBaseNameForOverride() {
        assertEquals("docker-compose.yml", ComposeMerge.baseNameForOverride("docker-compose.override.yml"))
        assertEquals("compose.yaml", ComposeMerge.baseNameForOverride("compose.override.yaml"))
        // Case-insensitive match, original case preserved in the result.
        assertEquals("Docker-Compose.YML", ComposeMerge.baseNameForOverride("Docker-Compose.Override.YML"))
        // Not an override file -> null.
        assertNull(ComposeMerge.baseNameForOverride("docker-compose.yml"))
        assertNull(ComposeMerge.baseNameForOverride("docker-compose.prod.yml"))
    }

    // ---- merge semantics --------------------------------------------------------------------

    fun testScalarOverlayReplacesAndIsFlagged() {
        val result = merge(
            """
            services:
              api:
                image: myapp:1
                restart: "no"
            """.trimIndent(),
            """
            services:
              api:
                image: myapp:2
            """.trimIndent(),
        )
        val api = result.merged.service("api")!!
        assertEquals("myapp:2", api.image)
        // Untouched by the overlay, kept from the base.
        assertEquals("no", api.restart)
        val changed = result.overriddenKeys["api"].orEmpty()
        assertTrue("image should be flagged as overridden", "image" in changed)
        assertFalse("restart was not touched", "restart" in changed)
    }

    fun testSequencesConcatenateAndDeduplicate() {
        val result = merge(
            """
            services:
              api:
                image: x
                ports:
                  - "8080:80"
                expose:
                  - "9000"
            """.trimIndent(),
            """
            services:
              api:
                ports:
                  - "8080:80"
                  - "443:443"
                expose:
                  - "9001"
            """.trimIndent(),
        )
        val api = result.merged.service("api")!!
        // "8080:80" appears in both but is kept once; the new overlay port is appended.
        assertEquals(listOf("8080:80", "443:443"), api.ports.map { it.raw })
        assertEquals(listOf("9000", "9001"), api.expose)
        assertTrue("ports" in result.overriddenKeys["api"].orEmpty())
    }

    fun testDependsOnNetworksAndEnvironmentUnion() {
        val result = merge(
            """
            services:
              api:
                image: x
                depends_on:
                  - db
                networks:
                  - front
                environment:
                  LOG_LEVEL: debug
              db:
                image: postgres:16
            """.trimIndent(),
            """
            services:
              api:
                depends_on:
                  - cache
                networks:
                  - back
                environment:
                  EXTRA: "1"
              cache:
                image: redis:7
            """.trimIndent(),
        )
        val api = result.merged.service("api")!!
        assertEquals(listOf("db", "cache"), api.dependsOn)
        assertEquals(setOf("front", "back"), api.networks)
        assertEquals(setOf("LOG_LEVEL", "EXTRA"), api.environmentKeys)
        for (key in listOf("depends_on", "networks", "environment")) {
            assertTrue("$key should be flagged", key in result.overriddenKeys["api"].orEmpty())
        }
        // The base service the overlay didn't mention survives.
        assertNotNull(result.merged.service("db"))
    }

    fun testOverlayHealthcheckWins() {
        val result = merge(
            """
            services:
              db:
                image: postgres:16
            """.trimIndent(),
            """
            services:
              db:
                healthcheck:
                  test: ["CMD", "pg_isready"]
                  interval: 10s
            """.trimIndent(),
        )
        val db = result.merged.service("db")!!
        assertTrue("overlay adds a healthcheck", db.hasHealthcheck)
        assertFalse(db.healthcheckDisabled)
        assertTrue("healthcheck" in result.overriddenKeys["db"].orEmpty())
    }

    fun testServiceOnlyInOverlayIsAddedAndFlaggedNew() {
        val result = merge(
            """
            services:
              api:
                image: x
            """.trimIndent(),
            """
            services:
              worker:
                image: worker:1
            """.trimIndent(),
        )
        assertEquals(setOf("api", "worker"), result.merged.serviceNames)
        assertTrue(MergeResult.NEW_SERVICE in result.overriddenKeys["worker"].orEmpty())
        // The pre-existing base service isn't flagged as changed.
        assertTrue(result.overriddenKeys["api"].isNullOrEmpty())
    }

    fun testMergedProjectKeepsBaseFileIdentity() {
        val result = merge(
            """
            services:
              api:
                image: x
            """.trimIndent(),
            """
            services:
              api:
                image: y
            """.trimIndent(),
        )
        // On-disk checks resolve relative paths against the base file's directory, so the merged
        // project must keep the base file's identity, not the overlay's.
        assertEquals("docker-compose.yml", result.merged.fileName)
    }
}
