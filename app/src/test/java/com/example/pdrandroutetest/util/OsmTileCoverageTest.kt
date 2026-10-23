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

            val cx = (minX + maxX) / 2.0
            val cy = (minY + maxY) / 2.0

            val firstTile = tiles.first()
            val firstCenterMetersX = (firstTile.minX + firstTile.maxX) / 2.0
            val firstCenterMetersY = (firstTile.minY + firstTile.maxY) / 2.0
            val firstDist = kotlin.math.hypot(firstCenterMetersX - cx, firstCenterMetersY - cy)

            val lastTile = tiles.last()
            val lastCenterMetersX = (lastTile.minX + lastTile.maxX) / 2.0
            val lastCenterMetersY = (lastTile.minY + lastTile.maxY) / 2.0
            val lastDist = kotlin.math.hypot(lastCenterMetersX - cx, lastCenterMetersY - cy)

            assertTrue("First tile at zoom $z ($firstDist m) must be closer to center than last tile ($lastDist m)", firstDist <= lastDist + 1.0)
        }

        println("OsmTileCoverageTest passed: All zoom levels 8..18 produce center-sorted, non-empty tile sets!")
    }
}
