package com.qrowsolutions.stackdoctor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.inspection.ServiceFieldWriter
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import com.qrowsolutions.stackdoctor.parser.ComposePsi
import com.qrowsolutions.stackdoctor.parser.FieldKind
import com.qrowsolutions.stackdoctor.parser.ServiceField
import com.qrowsolutions.stackdoctor.parser.ServiceFieldEdit
import com.qrowsolutions.stackdoctor.parser.ServiceFields
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

class ServiceEditTest : BasePlatformTestCase() {

    private fun configure(text: String) = myFixture.configureByText("docker-compose.yml", text) as YAMLFile

    private fun fields(file: YAMLFile, service: String): List<ServiceField> {
        val body = ComposePsi.serviceKeyValue(file, service)!!.value as YAMLMapping
        return ServiceFields.read(body)
    }

    private fun field(file: YAMLFile, service: String, key: String) =
        fields(file, service).first { it.key == key }

    private fun edit(file: YAMLFile, service: String, vararg edits: ServiceFieldEdit) {
        WriteCommandAction.runWriteCommandAction(project) {
            ServiceFieldWriter.apply(project, file, service, edits.toList())
        }
    }

    // ---- Reading ----------------------------------------------------------------------------

    fun testReadsPresentFieldsFaithfully() {
        val file = configure(
            """
            services:
              api:
                image: myapp:1
                ports:
                  - "8080:80"
                  - "127.0.0.1:9000:9000"
                environment:
                  LOG_LEVEL: debug
                  TOKEN: abc
                restart: unless-stopped
            """.trimIndent(),
        )
        val fields = fields(file, "api")
        // Only present keys are returned.
        assertEquals(listOf("image", "ports", "environment", "restart"), fields.map { it.key })

        assertEquals("myapp:1", field(file, "api", "image").value)
        assertEquals("8080:80\n127.0.0.1:9000:9000", field(file, "api", "ports").value)
        // Map-form environment is shown as KEY=value lines, preserving values the model drops.
        val env = field(file, "api", "environment")
        assertEquals(FieldKind.ENV, env.kind)
        assertEquals("LOG_LEVEL=debug\nTOKEN=abc", env.value)
        assertEquals("unless-stopped", field(file, "api", "restart").value)
    }

    fun testConditionalDependsOnIsReadOnly() {
        val file = configure(
            """
            services:
              app:
                image: x
                depends_on:
                  db:
                    condition: service_healthy
              db:
                image: postgres:16
            """.trimIndent(),
        )
        val dependsOn = field(file, "app", "depends_on")
        assertFalse("conditional depends_on must not be editable", dependsOn.editable)
        assertEquals("db", dependsOn.value)
        assertNotNull(dependsOn.note)
    }

    // ---- Writing ----------------------------------------------------------------------------

    fun testEditScalarRewritesValue() {
        val file = configure(
            """
            services:
              api:
                image: myapp:1
                restart: always
            """.trimIndent(),
        )
        edit(file, "api", ServiceFieldEdit("image", FieldKind.SCALAR, "myapp:2"))

        assertEquals("myapp:2", ComposeParser.parse(file)!!.service("api")!!.image)
        // Untouched keys survive.
        assertEquals("always", ComposeParser.parse(file)!!.service("api")!!.restart)
    }

    fun testClearingScalarRemovesKey() {
        val file = configure(
            """
            services:
              api:
                image: myapp:1
                restart: always
            """.trimIndent(),
        )
        edit(file, "api", ServiceFieldEdit("restart", FieldKind.SCALAR, ""))

        assertNull(ComposeParser.parse(file)!!.service("api")!!.restart)
        assertEquals("myapp:1", ComposeParser.parse(file)!!.service("api")!!.image)
    }

    fun testEditListRewritesAsBlockSequence() {
        val file = configure(
            """
            services:
              api:
                image: myapp:1
                ports:
                  - "8080:80"
            """.trimIndent(),
        )
        edit(file, "api", ServiceFieldEdit("ports", FieldKind.LIST, "8080:80\n443:443"))

        val ports = ComposeParser.parse(file)!!.service("api")!!.ports.map { it.raw }
        assertEquals(listOf("8080:80", "443:443"), ports)
    }

    fun testEditEnvironmentWritesKeyValueList() {
        val file = configure(
            """
            services:
              api:
                image: myapp:1
                environment:
                  LOG_LEVEL: debug
            """.trimIndent(),
        )
        edit(file, "api", ServiceFieldEdit("environment", FieldKind.ENV, "LOG_LEVEL=info\nEXTRA=1"))

        val keys = ComposeParser.parse(file)!!.service("api")!!.environmentKeys
        assertEquals(setOf("LOG_LEVEL", "EXTRA"), keys)
        // Re-reading the form should surface the new values too.
        assertEquals("LOG_LEVEL=info\nEXTRA=1", field(file, "api", "environment").value)
    }

    fun testAddingNewServiceKeyKeepsOthers() {
        val file = configure(
            """
            services:
              api:
                image: myapp:1
              db:
                image: postgres:16
            """.trimIndent(),
        )
        // A scalar that wasn't present is appended at the service's indentation.
        edit(file, "api", ServiceFieldEdit("restart", FieldKind.SCALAR, "on-failure"))

        assertEquals("on-failure", ComposeParser.parse(file)!!.service("api")!!.restart)
        // The neighbouring service is untouched.
        assertEquals("postgres:16", ComposeParser.parse(file)!!.service("db")!!.image)
    }
}
