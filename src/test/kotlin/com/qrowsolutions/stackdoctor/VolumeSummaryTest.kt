package com.qrowsolutions.stackdoctor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.qrowsolutions.stackdoctor.model.VolumeKind
import com.qrowsolutions.stackdoctor.model.VolumeSummary
import com.qrowsolutions.stackdoctor.parser.ComposeParser
import org.jetbrains.yaml.psi.YAMLFile

class VolumeSummaryTest : BasePlatformTestCase() {

    private fun parse(text: String) =
        ComposeParser.parse(myFixture.configureByText("docker-compose.yml", text) as YAMLFile)!!

    private val sample = """
        services:
          db:
            image: postgres:16
            volumes:
              - pgdata:/var/lib/postgresql/data
              - ./init.sql:/docker-entrypoint-initdb.d/init.sql
              - /var/lib/postgresql/scratch
          web:
            image: nginx
        volumes:
          pgdata: {}
    """.trimIndent()

    fun testClassifiesEachMountKind() {
        val db = parse(sample).service("db")!!
        val byKind = db.volumes.associate { (it.source ?: it.target) to it.kind }
        assertEquals(VolumeKind.NAMED, byKind["pgdata"])
        assertEquals(VolumeKind.BIND, byKind["./init.sql"])
        // Anonymous volume: only a container path, no source.
        assertEquals(VolumeKind.ANONYMOUS, byKind["/var/lib/postgresql/scratch"])
    }

    fun testPersistenceGroupsNamedAndBind() {
        val db = parse(sample).service("db")!!
        assertTrue(db.volumes.single { it.source == "pgdata" }.isPersistent)
        assertTrue(db.volumes.single { it.source == "./init.sql" }.isPersistent)
        assertFalse(db.volumes.single { it.source == null }.isPersistent)
    }

    fun testSummaryRollsUpByKind() {
        val db = VolumeSummary.of(parse(sample).service("db")!!)
        assertEquals(1, db.named.size)
        assertEquals(1, db.binds.size)
        assertEquals(1, db.anonymous.size)
        assertEquals(2, db.persistentCount) // named + bind
        assertTrue(db.hasPersistent)
        assertTrue(db.hasAnonymous)
        assertEquals(3, db.total)
        assertFalse(db.isEmpty)
    }

    fun testSummaryEmptyForServiceWithoutVolumes() {
        val web = VolumeSummary.of(parse(sample).service("web")!!)
        assertTrue(web.isEmpty)
        assertFalse(web.hasPersistent)
        assertFalse(web.hasAnonymous)
        assertEquals(0, web.total)
    }
}
