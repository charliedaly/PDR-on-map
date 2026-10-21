# Implementation Plan — Pre-Warped Offline OSM Map Tiles, Projection Testing & Irish Alphanumeric Grid Verification

This implementation plan refactors all OpenStreetMap (OSM) tile projection, coordinate transformation, and warping math out of `MapScreen.kt` into a standalone utility module (`OsmTileProjectionUtils.kt`), creates a comprehensive JVM unit test suite (`OsmTileProjectionTest.kt`), fixes Alphanumeric Irish Grid letter handling across all 25 grid squares, and adds multi-square grid unit tests.

## User Review Required

> [!IMPORTANT]
> **Pure Metric UI Canvas (`MapScreen.kt`)**:
> `MapScreen.kt` operates **100% in Irish National Grid metric $x, y$ coordinates (meters)**. It requires zero knowledge of WGS84. All conversions to/from WGS84 lat/lon happen strictly inside `CoordinateUtils.kt` and `OsmTileProjectionUtils.kt`.

> [!NOTE]
> **Dynamic Alphanumeric Grid Square Letter Handling**:
> The 25 $100\text{km} \times 100\text{km}$ Irish Grid squares (`A` through `Z`) are dynamically mapped using exact global Easting/Northing offsets ($0, 100k, 200k, 300k, 400k \text{ m}$). No hardcoded letter (such as `'W'`) is used anywhere.

---

## Proposed Changes

### 1. Standalone OSM Tile Projection Utility (`OsmTileProjectionUtils.kt`)

#### [NEW] [OsmTileProjectionUtils.kt](file:///home/androidcharlied/AndroidStudioProjects/HikingTool/app/src/main/java/com/example/pdrandroutetest/util/OsmTileProjectionUtils.kt)

- **Pure Kotlin Module**:
  - `lonToTileX(lon, zoom)` & `latToTileY(lat, zoom)`: Converts WGS84 lat/lon to Slippy Map tile indices.
  - `tileToWgs84Bounds(zoom, tileX, tileY)`: Computes exact WGS84 latitude/longitude boundaries for tile $(z, x, y)$.
  - `tileToMetricCorners(zoom, tileX, tileY)`: Converts WGS84 tile corners to Irish National Grid metric locations (NW, NE, SE, SW).
  - `getTilesForMetricArea(minX, maxX, minY, maxY, zoom)`: Returns a deduplicated, monotonically ordered list of tiles covering the metric area.

---

### 2. Comprehensive Projection & Coordinate Unit Test Suite

#### [NEW] [OsmTileProjectionTest.kt](file:///home/androidcharlied/AndroidStudioProjects/HikingTool/app/src/test/java/com/example/pdrandroutetest/util/OsmTileProjectionTest.kt)

- **Unit Test Cases**:
  - `testTileUniquenessInAreaQuery`: Verifies no tile coordinates repeat across any bounding box query.
  - `testZoomSubdivisionHalving`: Confirms $z \rightarrow z+1$ produces 4 child tiles each covering half the physical metric width and height ($1/2 \times 1/2$ area halving).
  - `testMetricCornerMonotonicity`: Validates that Easting increases West-to-East and Northing increases South-to-North without inversion.
  - `testTilePhysicalMetricDimensions`: Checks physical metric tile spans across zoom levels 8 through 18.

#### [MODIFY] [CoordinateUtilsTest.kt](file:///home/androidcharlied/AndroidStudioProjects/HikingTool/app/src/test/java/com/example/pdrandroutetest/util/CoordinateUtilsTest.kt)

- **Alphanumeric Irish Grid Letter Tests**:
  - Tests parsing and conversion for grid references across all 25 Irish Grid squares (`H` Dublin/Wicklow, `V` Kerry, `O` Meath/Louth, `B` Antrim, `S` Tipperary, `M` Galway, etc.).
  - Verifies round-trip conversions ($\text{Alphanumeric} \rightarrow \text{Global Metric } (x, y) \rightarrow \text{WGS84 Lat/Lon} \rightarrow \text{Alphanumeric}$) match to within 1 meter precision across all squares.

---

### 3. Refactoring `OsmTileManager.kt` & `MapScreen.kt`

#### [MODIFY] [OsmTileManager.kt](file:///home/androidcharlied/AndroidStudioProjects/HikingTool/app/src/main/java/com/example/pdrandroutetest/util/OsmTileManager.kt)

- Delegate tile bounding box and tile area queries to `OsmTileProjectionUtils`.

#### [MODIFY] [MapScreen.kt](file:///home/androidcharlied/AndroidStudioProjects/HikingTool/app/src/main/java/com/example/pdrandroutetest/ui/MapScreen.kt)

- Remove all internal tile projection and matrix calculation code, calling `OsmTileProjectionUtils` directly.

---

## Verification Plan

### Automated Tests
- Run full unit test suite including `OsmTileProjectionTest` and `CoordinateUtilsTest`:
  `app:testDebugUnitTest`
- Confirm all 40+ tests pass with 100% success.

### Manual Verification
- Verify build completes with zero warnings or errors:
  `app:assembleDebug`
