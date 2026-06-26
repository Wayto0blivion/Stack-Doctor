package com.qrowsolutions.stackdoctor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.analysis.HealthcheckGenerator
import com.qrowsolutions.stackdoctor.inspection.HealthcheckWriter
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import org.jetbrains.yaml.psi.YAMLFile

class HealthcheckTest : BasePlatformTestCase() {

    private fun parse(text: String) =
        ComposeParser.parse(myFixture.configureByText("docker-compose.yml", text) as YAMLFile)!!

    fun testGeneratesProbeFromImage() {
        val project = parse(
            """
            services:
              db:
                image: postgres:16
              cache:
                image: redis:7
              api:
                image: myorg/api:latest
                expose: ["8080"]
              mystery:
                image: some/unknown:1
            """.trimIndent(),
        )
        assertTrue(HealthcheckGenerator.generate(project.service("db")!!)!!.test.contains("pg_isready"))
        assertTrue(HealthcheckGenerator.generate(project.service("cache")!!)!!.test.contains("redis-cli"))
        assertTrue(HealthcheckGenerator.generate(project.service("api")!!)!!.test.contains("http://localhost:8080"))
        // No image hint and no port to probe -> nothing meaningful to suggest.
        assertNull(HealthcheckGenerator.generate(project.service("mystery")!!))
    }

    fun testWriterInsertsHealthcheckThatParses() {
        val text = """
            services:
              db:
                image: postgres:16
              app:
                image: myapp
                depends_on:
                  - db
        """.trimIndent()
        val file = myFixture.configureByText("docker-compose.yml", text) as YAMLFile
        val svc = ComposeParser.parse(file)!!.service("db")!!
        val hc = HealthcheckGenerator.generate(svc)!!

        WriteCommandAction.runWriteCommandAction(project) {
            HealthcheckWriter.apply(project, file, listOf("db" to hc))
        }

        val reparsed = ComposeParser.parse(file)!!.service("db")!!
        assertTrue("db should now declare a healthcheck", reparsed.hasHealthcheck)
        assertFalse(reparsed.healthcheckDisabled)
        // The other service must be untouched.
        assertFalse(ComposeParser.parse(file)!!.service("app")!!.hasHealthcheck)
    }
}
