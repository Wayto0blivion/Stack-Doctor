package com.qrowsolutions.stackdoctor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import com.qrowsolutions.stackdoctor.ui.ServiceCategory
import org.jetbrains.yaml.psi.YAMLFile

class ServiceCategoryTest : BasePlatformTestCase() {

    private fun parse(text: String) =
        ComposeParser.parse(myFixture.configureByText("docker-compose.yml", text) as YAMLFile)!!

    fun testCategorisesFromNameAndImage() {
        val project = parse(
            """
            services:
              database:
                build: ./db
              userdb:
                build: ./userdb
              cache:
                image: redis:7
              kafka:
                image: apache/kafka:latest
              proxy:
                image: nginx:alpine
              api:
                build: ./api
              blob:
                image: some/unknown:1
            """.trimIndent(),
        )
        // A service clearly named like a database is detected even with no recognisable image.
        assertEquals(ServiceCategory.DATABASE, ServiceCategory.of(project.service("database")!!))
        assertEquals(ServiceCategory.DATABASE, ServiceCategory.of(project.service("userdb")!!))
        assertEquals(ServiceCategory.CACHE, ServiceCategory.of(project.service("cache")!!))
        // 'apache/kafka' must read as a queue, not a proxy (Apache is just the image org here).
        assertEquals(ServiceCategory.QUEUE, ServiceCategory.of(project.service("kafka")!!))
        assertEquals(ServiceCategory.PROXY, ServiceCategory.of(project.service("proxy")!!))
        assertEquals(ServiceCategory.WEB, ServiceCategory.of(project.service("api")!!))
        assertEquals(ServiceCategory.GENERIC, ServiceCategory.of(project.service("blob")!!))
    }
}
