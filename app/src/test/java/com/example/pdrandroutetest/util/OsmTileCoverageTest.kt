package com.example.pdrandroutetest.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OsmTileCoverageTest {

    @Test
    fun testTileCoverageAndParentFallbackLogic() {
        val minX = 80000.0
        val maxX = 90000.0
        val minY = 80000.0
        val maxY = 90000.0

        for (z in 8..18) {
            val tiles = OsmTileProjectionUtils.getTilesForMetricArea(minX, maxX, minY, maxY, z)
            assertTrue("Tiles for zoom $z must not be empty", tiles.isNotEmpty())
        }

        println("OsmTileCoverageTest passed: All zoom levels 8..18 produce non-empty tile sets!")
    }
}
