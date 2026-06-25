package com.qrowsolutions.stackdoctor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.diagnostics.Severity
import com.qrowsolutions.stackdoctor.diagnostics.StackDoctor
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import org.jetbrains.yaml.psi.YAMLFile

class ComposeParserTest : BasePlatformTestCase() {

    private val sample = """
        services:
          frontend:
            build: ./frontend
            ports:
              - "3000:3000"
            depends_on:
              - backend
          backend:
            image: myapp:latest
            ports:
              - "127.0.0.1:8000:8000"
            depends_on:
              - database
              - ghost
          database:
            image: postgres:16
            volumes:
              - pgdata:/var/lib/postgresql/data
        volumes: {}
    """.trimIndent()

    private fun parse(text: String) =
        ComposeParser.parse(myFixture.configureByText("docker-compose.yml", text) as YAMLFile)!!

    fun testParsesServicesAndDependencies() {
        val project = parse(sample)
        assertEquals(setOf("frontend", "backend", "database"), project.serviceNames)
        assertEquals(listOf("backend"), project.service("frontend")!!.dependsOn)
        assertTrue(project.service("frontend")!!.hasBuild)
        assertEquals("postgres:16", project.service("database")!!.image)
    }

    fun testParsesPortSyntaxes() {
        val project = parse(sample)
        val backendPort = project.service("backend")!!.ports.single()
        assertEquals("127.0.0.1", backendPort.hostIp)
        assertEquals("8000", backendPort.hostPort)
        assertEquals("8000", backendPort.containerPort)
        assertTrue(backendPort.isLoopbackBound)

        val frontendPort = project.service("frontend")!!.ports.single()
        assertNull(frontendPort.hostIp)
        assertFalse(frontendPort.isLoopbackBound)
    }

    fun testDoctorFindsKeyIssues() {
        val project = parse(sample)
        val ids = StackDoctor.run(project, baseDir = null).map { it.id }.toSet()
        assertTrue("unknown depends_on", "unknown-depends-on" in ids)
        assertTrue("loopback port", "loopback-bound-port" in ids)
        assertTrue("missing healthcheck", "missing-healthcheck" in ids)
        assertTrue("undeclared named volume", "undeclared-volume" in ids)
    }

    fun testDetectsDependencyCycle() {
        val cyclic = """
            services:
              a:
                image: x
                depends_on: [b]
              b:
                image: y
                depends_on: [a]
        """.trimIndent()
        val diags = StackDoctor.run(parse(cyclic), baseDir = null)
        assertTrue(diags.any { it.id == "dependency-cycle" && it.severity == Severity.ERROR })
    }

    fun testDetectsPortConflict() {
        val conflict = """
            services:
              a:
                image: x
                ports: ["8080:80"]
              b:
                image: y
                ports: ["8080:80"]
        """.trimIndent()
        val diags = StackDoctor.run(parse(conflict), baseDir = null)
        assertTrue(diags.any { it.id == "port-conflict" })
    }
}
