package com.qrowsolutions.stackdoctor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.inspection.ComposeDoctorInspection

class ComposeInspectionTest : BasePlatformTestCase() {

    fun testLoopbackQuickFixPublishesOnAllInterfaces() {
        myFixture.enableInspections(ComposeDoctorInspection())
        myFixture.configureByText(
            "docker-compose.yml",
            """
            services:
              backend:
                image: x
                ports:
                  - "127.0.0.1:8000:8000"
            """.trimIndent(),
        )
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text.contains("Publish on all interfaces") }
        assertNotNull("loopback quick-fix offered", fix)
        myFixture.launchAction(fix!!)
        val text = myFixture.file.text
        assertFalse("host IP removed", text.contains("127.0.0.1"))
        assertTrue("port preserved", text.contains("8000:8000"))
    }

    fun testDeclareVolumeQuickFixCreatesSection() {
        myFixture.enableInspections(ComposeDoctorInspection())
        myFixture.configureByText(
            "docker-compose.yml",
            """
            services:
              db:
                image: postgres
                volumes:
                  - pgdata:/var/lib/postgresql/data
            """.trimIndent(),
        )
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text.contains("Declare volumes entry") }
        assertNotNull("declare-volume quick-fix offered", fix)
        myFixture.launchAction(fix!!)
        assertTrue("top-level volumes section declares pgdata", myFixture.file.text.contains("volumes:\n  pgdata:"))
    }

    fun testDeclareVolumeQuickFixHandlesEmptyFlowSection() {
        myFixture.enableInspections(ComposeDoctorInspection())
        myFixture.configureByText(
            "docker-compose.yml",
            """
            services:
              db:
                image: postgres
                volumes:
                  - pgdata:/var/lib/postgresql/data
            volumes: {}
            """.trimIndent(),
        )
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text.contains("Declare volumes entry") }
        assertNotNull("declare-volume quick-fix offered", fix)
        myFixture.launchAction(fix!!)
        val text = myFixture.file.text
        assertFalse("flow-empty mapping replaced", text.contains("volumes: {}"))
        assertTrue("pgdata now declared", text.contains("pgdata:"))
    }
}
