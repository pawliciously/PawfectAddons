package dev.pawfect.addons.features.dungeon.routes

import dev.pawfect.addons.config.features.SecretRoutesConfig.Source
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteDataTest {

    @BeforeTest
    fun load() = RouteData.load()

    @Test
    fun bundledSetsLoad() {
        println(RouteData.report)
        assertTrue(RouteData.report.startsWith("Default 10"), RouteData.report)
        assertTrue("FlameOfWar SRM 11" in RouteData.report, RouteData.report)
        assertTrue("Stella 10" in RouteData.report, RouteData.report)
    }

    @Test
    fun eachSetIsItsOwn() {
        val default = assertNotNull(RouteData.routesFor("Sewer-7", Source.DEFAULT))
        val fow = assertNotNull(RouteData.routesFor("Sewer-7", Source.FLAMEOFWAR))
        assertEquals(Source.DEFAULT, default.from)
        assertEquals(Source.FLAMEOFWAR, fow.from)
        assertNull(default.note)
        assertNull(fow.note)
    }

    @Test
    fun recordedFallsBackToDefault() {
        val picked = assertNotNull(RouteData.routesFor("Sewer-7", Source.RECORDED))
        assertEquals(Source.DEFAULT, picked.from)
        assertEquals("No recording for this room, using Default", picked.note)
    }

    @Test
    fun oursOnlyOverDefaultAndStella() {
        assertTrue(assertNotNull(RouteData.routesFor("Knight-3", Source.DEFAULT)).ours)
        assertTrue(assertNotNull(RouteData.routesFor("Knight-3", Source.STELLA)).ours)
        val fow = RouteData.routesFor("Knight-3", Source.FLAMEOFWAR)
        if (fow != null) assertTrue(!fow.ours || fow.from != Source.FLAMEOFWAR)
    }

    @Test
    fun brokenStellaSaysWhy() {
        val picked = assertNotNull(RouteData.routesFor("Sewer-7", Source.STELLA))
        assertEquals(Source.DEFAULT, picked.from)
        assertEquals("Stella's route here doesn't work, using Default", picked.note)
    }
}
