package com.example.pdrandroutetest.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmTileProjectionTest {

    @Test
    fun testTileUniquenessInAreaQuery() {
        val minX = 80000.0
        val maxX = 95000.0
        val minY = 80000.0
        val maxY = 95000.0

        for (z in 8..15) {
            val tiles = OsmTileProjectionUtils.getTilesForMetricArea(minX, maxX, minY, maxY, zoom = z)
            val uniqueKeys = tiles.map { "${it.zoom}/${it.tileX}/${it.tileY}" }.toSet()

            assertEquals("All queried tiles at zoom $z must be strictly unique", tiles.size, uniqueKeys.size)
        }
    }

    @Test
    fun testZoomSubdivisionHalving() {
        val z = 10
        val tileX = 495
        val tileY = 330

        val parentCorners = OsmTileProjectionUtils.tileToMetricCorners(z, tileX, tileY)

        val child00 = OsmTileProjectionUtils.tileToMetricCorners(z + 1, tileX * 2, tileY * 2)

        val parentSpanX = parentCorners.spanX
        val parentSpanY = parentCorners.spanY

        val childSpanX = child00.spanX
        val childSpanY = child00.spanY

        // Child physical width and height must be approximately 1/2 of parent
        assertEquals(0.5, childSpanX / parentSpanX, 0.05)
        assertEquals(0.5, childSpanY / parentSpanY, 0.05)

        // Confirm child tile connects to parent bounding box
        assertEquals(parentCorners.minX, child00.minX, 500.0)
    }

    @Test
    fun testMetricCornerMonotonicity() {
        val corners = OsmTileProjectionUtils.tileToMetricCorners(10, 495, 330)

        assertTrue("East corner X must be greater than West corner X", corners.neMetric.x > corners.nwMetric.x)
        assertTrue("North corner Y must be greater than South corner Y", corners.nwMetric.y > corners.swMetric.y)
    }

    @Test
    fun testTilePhysicalMetricDimensions() {
        var prevSpanX = Double.MAX_VALUE

        for (z in 8..15) {
            val tx = OsmTileProjectionUtils.lonToTileX(-9.5, z)
            val ty = OsmTileProjectionUtils.latToTileY(52.0, z)
            val corners = OsmTileProjectionUtils.tileToMetricCorners(z, tx, ty)
            val currentSpanX = corners.spanX

            assertTrue("Metric tile span at zoom $z ($currentSpanX) must be smaller than zoom ${z - 1} ($prevSpanX)", currentSpanX < prevSpanX)
            prevSpanX = currentSpanX
        }
    }
}
