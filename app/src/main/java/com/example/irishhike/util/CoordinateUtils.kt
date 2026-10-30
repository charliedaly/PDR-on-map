package com.example.irishhike.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*
import com.example.irishhike.model.Location

// --- Internal Data Classes & Maps ---

private data class LatLon(val latitude: Double, val longitude: Double)

private data class IrishGridReference(
    val letter: Char,
    val easting: Double,
    val northing: Double
) {
    override fun toString(): String {
        val e = easting.toInt().toString().padStart(5, '0')
        val n = northing.toInt().toString().padStart(5, '0')
        return "$letter $e$n"
    }
}

private data class FullCoordinates(val x: Double, val y: Double)

private data class GridOffset(val eastingOffset: Double, val northingOffset: Double)

private val letterCorrectionMap = mapOf(
    'A' to GridOffset(70.07, 146.81), 'B' to GridOffset(86.22, 147.32),
    'C' to GridOffset(106.15, 148.39), 'D' to GridOffset(121.62, 149.54),
    'F' to GridOffset(70.73, 125.83), 'G' to GridOffset(87.12, 126.65),
    'H' to GridOffset(107.14, 127.72), 'J' to GridOffset(122.88, 128.59),
    'L' to GridOffset(71.34, 102.25), 'M' to GridOffset(88.16, 103.47),
    'N' to GridOffset(108.22, 104.51), 'O' to GridOffset(124.39, 105.09),
    'Q' to GridOffset(72.04, 78.80), 'R' to GridOffset(89.28, 80.07),
    'S' to GridOffset(109.23, 81.13), 'T' to GridOffset(125.81, 81.69),
    'V' to GridOffset(72.71, 57.07), 'W' to GridOffset(90.45, 58.27),
    'X' to GridOffset(110.09, 59.33), 'Y' to GridOffset(127.15, 59.99)
)

private val dynamic10kmOffsetMap = mutableMapOf<String, GridOffset>()

private val gridLetters = arrayOf(
    arrayOf('A', 'B', 'C', 'D', 'E'),
    arrayOf('F', 'G', 'H', 'J', 'K'),
    arrayOf('L', 'M', 'N', 'O', 'P'),
    arrayOf('Q', 'R', 'S', 'T', 'U'),
    arrayOf('V', 'W', 'X', 'Y', 'Z')
)

private val gridLetterMap = mapOf(
    'A' to Pair(0.0, 400000.0), 'B' to Pair(100000.0, 400000.0), 'C' to Pair(200000.0, 400000.0), 'D' to Pair(300000.0, 400000.0), 'E' to Pair(400000.0, 400000.0),
    'F' to Pair(0.0, 300000.0), 'G' to Pair(100000.0, 300000.0), 'H' to Pair(200000.0, 300000.0), 'J' to Pair(300000.0, 300000.0), 'K' to Pair(400000.0, 300000.0),
    'L' to Pair(0.0, 200000.0), 'M' to Pair(100000.0, 200000.0), 'N' to Pair(200000.0, 200000.0), 'O' to Pair(300000.0, 200000.0), 'P' to Pair(400000.0, 200000.0),
    'Q' to Pair(0.0, 100000.0), 'R' to Pair(100000.0, 100000.0), 'S' to Pair(200000.0, 100000.0), 'T' to Pair(300000.0, 100000.0), 'U' to Pair(400000.0, 100000.0),
    'V' to Pair(0.0, 0.0),      'W' to Pair(100000.0, 0.0),      'X' to Pair(200000.0, 0.0),      'Y' to Pair(300000.0, 0.0),      'Z' to Pair(400000.0, 0.0)
)

private fun loadIrishGridOffsetsInternal(context: Context, fileName: String = "irish_grid_offsets_10km.json") {
    try {
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(jsonString)

        dynamic10kmOffsetMap.clear()
        for (key in jsonObject.keys()) {
            val array = jsonObject.getJSONArray(key)
            dynamic10kmOffsetMap[key] = GridOffset(array.getDouble(0), array.getDouble(1))
        }
        Log.d("CoordinateUtils", "Loaded ${dynamic10kmOffsetMap.size} 10km grid offsets.")
    } catch (e: Exception) {
        Log.e("CoordinateUtils", "Failed to load 10km offsets: ${e.message}. Using 100km fallbacks.")
    }
}

// --- Internal Irish Grid Converter ---

private object IrishGridConverter {
    private const val A = 6377340.189
    private const val B = 6356034.446
    private const val F0 = 1.000035

    private const val LAT0 = 53.5
    private const val LON0 = -8.0
    private const val N0 = 250000.0
    private const val E0 = 200000.0

    private const val WGS84_A = 6378137.0
    private const val WGS84_B = 6356752.3142

    private val gridLetters = arrayOf(
        arrayOf('A', 'B', 'C', 'D', 'E'),
        arrayOf('F', 'G', 'H', 'J', 'K'),
        arrayOf('L', 'M', 'N', 'O', 'P'),
        arrayOf('Q', 'R', 'S', 'T', 'U'),
        arrayOf('V', 'W', 'X', 'Y', 'Z')
    )

    fun Wgs84ToIrishGrid(latLon: LatLon): IrishGridReference {
        val irl75 = wgs84ToIrl75Internal(latLon)
        val rawRef = projectToGridInternal(irl75)

        val correction = getBestOffsetInternal(rawRef.letter, rawRef.easting, rawRef.northing)
        return IrishGridReference(
            letter = rawRef.letter,
            easting = rawRef.easting + correction.eastingOffset,
            northing = rawRef.northing + correction.northingOffset
        )
    }

    fun IrishGridToWgs84(gridRefStr: String): LatLon {
        val clean = gridRefStr.replace(Regex("[^A-Z0-9]"), "").uppercase()
        require(clean.length >= 2) { "Invalid grid reference: $gridRefStr" }
        val letter = clean[0]

        val rawXY = parseGridReferenceToXYInternal(clean)
        val roughLocalE = rawXY.x - getGridLetterEastingOffsetInternal(letter)
        val roughLocalN = rawXY.y % 100000.0

        val correction = getBestOffsetInternal(letter, roughLocalE, roughLocalN)
        val unadjustedX = rawXY.x - correction.eastingOffset
        val unadjustedY = rawXY.y - correction.northingOffset

        val irl75 = inverseProjectToLatLonInternal(unadjustedX, unadjustedY)
        return irl75ToWgs84Internal(irl75)
    }

    fun getBestOffsetPublic(letter: Char, localEasting: Double, localNorthing: Double): GridOffset {
        return getBestOffsetInternal(letter, localEasting, localNorthing)
    }

    fun metricXYToWgs84(unadjustedX: Double, unadjustedY: Double): LatLon {
        val irl75 = inverseProjectToLatLonInternal(unadjustedX, unadjustedY)
        return irl75ToWgs84Internal(irl75)
    }

    private fun getBestOffsetInternal(letter: Char, localEasting: Double, localNorthing: Double): GridOffset {
        // Offset tables disabled for pure mathematical baseline transformation
        return GridOffset(0.0, 0.0)
    }

    private fun getGridLetterEastingOffsetInternal(letter: Char): Double {
        val columns = mapOf(
            'A' to 0, 'F' to 0, 'L' to 0, 'Q' to 0, 'V' to 0,
            'B' to 1, 'G' to 1, 'M' to 1, 'R' to 1, 'W' to 1,
            'C' to 2, 'H' to 2, 'N' to 2, 'S' to 2, 'X' to 2,
            'D' to 3, 'J' to 3, 'O' to 3, 'T' to 3, 'Y' to 3,
            'E' to 4, 'K' to 4, 'P' to 4, 'U' to 4, 'Z' to 4
        )
        return ((columns[letter.uppercaseChar()] ?: 0) * 100000).toDouble()
    }

    private fun parseGridReferenceToXYInternal(cleanRef: String): FullCoordinates {
        val letter = cleanRef[0]
        val numericPart = cleanRef.substring(1)
        val halfLen = numericPart.length / 2

        val localE = numericPart.substring(0, halfLen).padEnd(5, '0').take(5).toDouble()
        val localN = numericPart.substring(halfLen).padEnd(5, '0').take(5).toDouble()

        var baseE = 0.0
        var baseN = 0.0
        outer@ for (r in gridLetters.indices) {
            for (c in gridLetters[r].indices) {
                if (gridLetters[r][c] == letter) {
                    baseE = c * 100000.0
                    baseN = (4 - r) * 100000.0
                    break@outer
                }
            }
        }
        return FullCoordinates(baseE + localE, baseN + localN)
    }

    // -------------------------------------------------------------------------
    // EPSG:1641 / EPSG:1753 Standard 7-Parameter Helmert Transformation
    // TM65 (Ireland 1965 / IRL75) <-> WGS84 / ETRS89
    // Position Vector (Bursa-Wolf) convention
    // -------------------------------------------------------------------------
    private const val HELMERT_DX = 482.530           // Translation X (meters)
    private const val HELMERT_DY = -130.596          // Translation Y (meters)
    private const val HELMERT_DZ = 564.557           // Translation Z (meters)
    private const val HELMERT_S  = 8.15e-6           // Scale difference (+8.15 ppm)

    // Rotations in arcseconds converted to radians:
    // rx = -1.042", ry = -0.214", rz = -0.631"
    private val HELMERT_RX = Math.toRadians(-1.042 / 3600.0)
    private val HELMERT_RY = Math.toRadians(-0.214 / 3600.0)
    private val HELMERT_RZ = Math.toRadians(-0.631 / 3600.0)

    /**
     * Converts WGS84 Latitude/Longitude to Ireland 1965 (IRL75) Latitude/Longitude
     * using the inverse 7-parameter Position Vector Helmert transformation.
     */
    private fun wgs84ToIrl75Internal(latLon: LatLon): LatLon {
        val lat1 = Math.toRadians(latLon.latitude)
        val lon1 = Math.toRadians(latLon.longitude)

        // 1. Geodetic (WGS84) to Geocentric Cartesian (X, Y, Z)
        val eSq1 = (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_A * WGS84_A)
        val nu1 = WGS84_A / sqrt(1.0 - eSq1 * sin(lat1) * sin(lat1))
        val x1 = nu1 * cos(lat1) * cos(lon1)
        val y1 = nu1 * cos(lat1) * sin(lon1)
        val z1 = nu1 * (1.0 - eSq1) * sin(lat1)

        // 2. Inverse Position Vector Helmert: X_irl = (1 - s) * R^T * (X_wgs - T)
        val x0 = x1 - HELMERT_DX
        val y0 = y1 - HELMERT_DY
        val z0 = z1 - HELMERT_DZ
        val scale = 1.0 - HELMERT_S

        // Multiply by R^T (transposed rotation matrix)
        val x2 = scale * (x0 + HELMERT_RZ * y0 - HELMERT_RY * z0)
        val y2 = scale * (-HELMERT_RZ * x0 + y0 + HELMERT_RX * z0)
        val z2 = scale * (HELMERT_RY * x0 - HELMERT_RX * y0 + z0)

        // 3. Geocentric Cartesian to Geodetic on Airy 1830 Modified Ellipsoid
        val eSq2 = (A * A - B * B) / (A * A)
        val p = sqrt(x2 * x2 + y2 * y2)
        var lat2 = atan2(z2, p * (1.0 - eSq2))
        val lon2 = atan2(y2, x2)

        for (i in 0..4) {
            val nu2 = A / sqrt(1.0 - eSq2 * sin(lat2) * sin(lat2))
            lat2 = atan2(z2 + eSq2 * nu2 * sin(lat2), p)
        }

        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Converts Ireland 1965 (IRL75) Latitude/Longitude to WGS84 Latitude/Longitude
     * using the forward 7-parameter Position Vector Helmert transformation.
     */
    private fun irl75ToWgs84Internal(latLon: LatLon): LatLon {
        val lat1 = Math.toRadians(latLon.latitude)
        val lon1 = Math.toRadians(latLon.longitude)

        // 1. Geodetic (Airy 1830 Modified) to Geocentric Cartesian (X, Y, Z)
        val eSq1 = (A * A - B * B) / (A * A)
        val nu1 = A / sqrt(1.0 - eSq1 * sin(lat1) * sin(lat1))
        val x1 = nu1 * cos(lat1) * cos(lon1)
        val y1 = nu1 * cos(lat1) * sin(lon1)
        val z1 = nu1 * (1.0 - eSq1) * sin(lat1)

        // 2. Forward Position Vector Helmert: X_wgs = T + (1 + s) * R * X_irl
        val scale = 1.0 + HELMERT_S

        // Multiply by R (standard rotation matrix) and add translation
        val x2 = HELMERT_DX + scale * (x1 - HELMERT_RZ * y1 + HELMERT_RY * z1)
        val y2 = HELMERT_DY + scale * (HELMERT_RZ * x1 + y1 - HELMERT_RX * z1)
        val z2 = HELMERT_DZ + scale * (-HELMERT_RY * x1 + HELMERT_RX * y1 + z1)

        // 3. Geocentric Cartesian to Geodetic on WGS84 Ellipsoid
        val eSq2 = (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_A * WGS84_A)
        val p = sqrt(x2 * x2 + y2 * y2)
        var lat2 = atan2(z2, p * (1.0 - eSq2))
        val lon2 = atan2(y2, x2)

        for (i in 0..4) {
            val nu2 = WGS84_A / sqrt(1.0 - eSq2 * sin(lat2) * sin(lat2))
            lat2 = atan2(z2 + eSq2 * nu2 * sin(lat2), p)
        }

        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * Computes the meridional arc distance from the projection origin latitude (phi0)
     * to the given latitude (phi), scaled by central meridian scale factor F0.
     */
    private fun computeMeridionalArc(latRad: Double, phi0Rad: Double, b: Double, n: Double): Double {
        val n2 = n * n
        val n3 = n2 * n
        val dLat = latRad - phi0Rad
        val sumLat = latRad + phi0Rad

        val ma = (1.0 + n + (5.0 / 4.0) * n2 + (5.0 / 4.0) * n3) * dLat
        val mb = (3.0 * n + 3.0 * n2 + (21.0 / 8.0) * n3) * sin(dLat) * cos(sumLat)
        val mc = ((15.0 / 8.0) * n2 + (15.0 / 8.0) * n3) * sin(2.0 * dLat) * cos(2.0 * sumLat)
        val md = (35.0 / 24.0) * n3 * sin(3.0 * dLat) * cos(3.0 * sumLat)

        return b * F0 * (ma - mb + mc - md)
    }

    /**
     * Projects Ireland 1965 (IRL75 / TM65) Latitude/Longitude onto the
     * Irish National Grid (Transverse Mercator on Airy 1830 Modified Ellipsoid).
     *
     * Returns an IrishGridReference with 100km grid square letter and
     * local Easting/Northing in meters (0.0 to 99999.9m).
     */
    private fun projectToGridInternal(latLon: LatLon): IrishGridReference {
        val phi = Math.toRadians(latLon.latitude)
        val lam = Math.toRadians(latLon.longitude)
        val phi0 = Math.toRadians(LAT0)
        val lam0 = Math.toRadians(LON0)

        val e2 = (A * A - B * B) / (A * A)
        val n = (A - B) / (A + B)
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)
        val cosPhi3 = cosPhi * cosPhi * cosPhi
        val cosPhi5 = cosPhi3 * cosPhi * cosPhi
        val tanPhi2 = tanPhi * tanPhi
        val tanPhi4 = tanPhi2 * tanPhi2

        // nu: radius of curvature in prime vertical, scaled by F0
        val nu = A * F0 / sqrt(1.0 - e2 * sinPhi * sinPhi)
        // rho: radius of curvature in meridian, scaled by F0
        val rho = A * F0 * (1.0 - e2) / (1.0 - e2 * sinPhi * sinPhi).pow(1.5)
        val eta2 = (nu / rho) - 1.0

        val p = lam - lam0 // delta longitude in radians
        val p2 = p * p
        val p3 = p2 * p
        val p4 = p3 * p
        val p5 = p4 * p
        val p6 = p5 * p

        // --- Easting Calculation ---
        val term1 = nu * cosPhi * p
        val term2 = (nu / 6.0) * cosPhi3 * (nu / rho - tanPhi2) * p3
        val term3 = (nu / 120.0) * cosPhi5 * (
                5.0 - 18.0 * tanPhi2 + tanPhi4 + 14.0 * eta2 - 58.0 * tanPhi2 * eta2
                ) * p5

        val eastingTotal = E0 + term1 + term2 + term3

        // --- Northing Calculation ---
        val m = computeMeridionalArc(phi, phi0, B, n)
        val nTerm2 = (nu / 2.0) * sinPhi * cosPhi * p2
        val nTerm3 = (nu / 24.0) * sinPhi * cosPhi3 * (
                5.0 - tanPhi2 + 9.0 * eta2 + 4.0 * eta2 * eta2
                ) * p4
        val nTerm4 = (nu / 720.0) * sinPhi * cosPhi5 * (
                61.0 - 58.0 * tanPhi2 + tanPhi4
                ) * p6

        val northingTotal = N0 + m + nTerm2 + nTerm3 + nTerm4

        // --- Map into 100km Irish Grid Letter & Local Coordinates ---
        val col = (eastingTotal / 100000.0).toInt().coerceIn(0, 4)
        val row = (4 - (northingTotal / 100000.0).toInt()).coerceIn(0, 4)

        val localEasting = (eastingTotal % 100000.0).let { if (it < 0) it + 100000.0 else it }
        val localNorthing = (northingTotal % 100000.0).let { if (it < 0) it + 100000.0 else it }

        return IrishGridReference(
            letter = gridLetters[row][col],
            easting = localEasting,
            northing = localNorthing
        )
    }

    /**
     * Converts Irish National Grid full metric coordinates (unadjustedX, unadjustedY)
     * back into Ireland 1965 (IRL75) LatLon.
     */
    private fun inverseProjectToLatLonInternal(x: Double, y: Double): LatLon {
        val phi0 = Math.toRadians(LAT0)
        val lam0 = Math.toRadians(LON0)
        val e2 = (A * A - B * B) / (A * A)
        val n = (A - B) / (A + B)

        // Iterate to find the foot-point latitude (phiPrime)
        var phiPrime = phi0 + (y - N0) / (A * F0)
        while (true) {
            val m = computeMeridionalArc(phiPrime, phi0, B, n)
            val dPhi = (y - N0 - m) / (A * F0)
            phiPrime += dPhi
            if (abs(dPhi) < 1e-11) break
        }

        val sinPhi = sin(phiPrime)
        val cosPhi = cos(phiPrime)
        val tanPhi = tan(phiPrime)
        val tanPhi2 = tanPhi * tanPhi
        val tanPhi4 = tanPhi2 * tanPhi2

        val nuPrime = A * F0 / sqrt(1.0 - e2 * sinPhi * sinPhi)
        val rhoPrime = A * F0 * (1.0 - e2) / (1.0 - e2 * sinPhi * sinPhi).pow(1.5)
        val etaSqPrime = (nuPrime / rhoPrime) - 1.0

        val xRel = x - E0
        val xRel2 = xRel * xRel
        val xRel3 = xRel2 * xRel
        val xRel4 = xRel3 * xRel
        val xRel5 = xRel4 * xRel
        val xRel6 = xRel5 * xRel

        val secPhi = 1.0 / cosPhi

        // Latitude terms
        val vii = tanPhi / (2.0 * rhoPrime * nuPrime)
        val viii = (tanPhi / (24.0 * rhoPrime * nuPrime.pow(3))) * (
                5.0 + 3.0 * tanPhi2 + etaSqPrime - 9.0 * tanPhi2 * etaSqPrime
                )
        val ix = (tanPhi / (720.0 * rhoPrime * nuPrime.pow(5))) * (
                61.0 + 90.0 * tanPhi2 + 45.0 * tanPhi4
                )
        val lat = phiPrime - vii * xRel2 + viii * xRel4 - ix * xRel6

        // Longitude terms
        val xXi = secPhi / nuPrime
        val xXii = (secPhi / (6.0 * nuPrime.pow(3))) * (nuPrime / rhoPrime + 2.0 * tanPhi2)
        val xXiiA = (secPhi / (120.0 * nuPrime.pow(5))) * (
                5.0 + 28.0 * tanPhi2 + 24.0 * tanPhi4
                )
        val lon = lam0 + xXi * xRel - xXii * xRel3 + xXiiA * xRel5

        return LatLon(Math.toDegrees(lat), Math.toDegrees(lon))
    }
}

// --- Public CoordinateUtils Object exposing 4 Location-based methods ---

object CoordinateUtils {

    /**
     * Converts WGS84 latitude and longitude into Irish National Grid metric Location (x, y, altitude).
     * Uses 100km subgrid local Easting and Northing (0.0 to 99999.9m) for Alphanumeric Irish Grid compatibility.
     */
    fun latLonToMetric(lat: Double, lon: Double): Location {
        val gridRef = IrishGridConverter.Wgs84ToIrishGrid(LatLon(lat, lon))
        val baseOffset = gridLetterMap[gridRef.letter] ?: Pair(0.0, 0.0)
        return Location(
            x = baseOffset.first + gridRef.easting,
            y = baseOffset.second + gridRef.northing,
            altitude = 0.0
        )
    }

    /**
     * Converts Irish National Grid metric Location (x, y) into WGS84 latitude and longitude Pair(lat, lon).
     */
    fun metricToLatLon(location: Location): Pair<Double, Double> {
        val col = (location.x / 100000.0).toInt().coerceIn(0, 4)
        val row = (4 - (location.y / 100000.0).toInt()).coerceIn(0, 4)
        val letter = gridLetters[row][col]

        val localE = (location.x % 100000.0).let { if (it < 0) it + 100000.0 else it }
        val localN = (location.y % 100000.0).let { if (it < 0) it + 100000.0 else it }

        val letterDefault = letterCorrectionMap[letter] ?: GridOffset(0.0, 0.0)
        val roughUnadjustedE = (localE - letterDefault.eastingOffset).coerceIn(0.0, 99999.0)
        val roughUnadjustedN = (localN - letterDefault.northingOffset).coerceIn(0.0, 99999.0)

        val correction = IrishGridConverter.getBestOffsetPublic(letter, roughUnadjustedE, roughUnadjustedN)
        val unadjustedX = location.x - correction.eastingOffset
        val unadjustedY = location.y - correction.northingOffset

        val latLon = IrishGridConverter.metricXYToWgs84(unadjustedX, unadjustedY)
        return Pair(latLon.latitude, latLon.longitude)
    }

    /**
     * Computes metric Euclidean distance in meters between two Location objects.
     */
    fun getDistance(loc1: Location, loc2: Location): Double {
        return hypot(loc2.x - loc1.x, loc2.y - loc1.y)
    }

    /**
     * Computes grid bearing in degrees (0.0 to 360.0, clockwise from Grid North) from loc1 to loc2.
     */
    fun getBearing(loc1: Location, loc2: Location): Double {
        val dx = loc2.x - loc1.x
        val dy = loc2.y - loc1.y
        var angle = Math.toDegrees(atan2(dx, dy))
        if (angle < 0) {
            angle += 360.0
        }
        return angle
    }

    // --- Private Internal Utility Helpers ---

    private fun parseGridReferenceInternal(ref: String): Location {
        val cleaned = ref.trim().uppercase()
        if (cleaned.isEmpty()) return Location(0.0, 0.0)

        val hasLetter = cleaned[0] in 'A'..'Z' && cleaned[0] != 'I'
        val letter = if (hasLetter) cleaned[0] else 'V'
        val numericPart = if (hasLetter) cleaned.substring(1).replace(Regex("\\s+"), "") else cleaned.replace(Regex("\\s+"), "")

        val halfLen = numericPart.length / 2
        val eastingStr = if (halfLen > 0) numericPart.substring(0, halfLen) else "0"
        val northingStr = if (halfLen > 0) numericPart.substring(halfLen) else numericPart

        val padFactor = 5 - eastingStr.length
        val multiplier = 10.0.pow(padFactor.coerceAtLeast(0))

        val localE = (eastingStr.toDoubleOrNull() ?: 0.0) * multiplier
        val localN = (northingStr.toDoubleOrNull() ?: 0.0) * multiplier

        val baseOffset = gridLetterMap[letter] ?: Pair(0.0, 0.0)
        val globalE = baseOffset.first + localE
        val globalN = baseOffset.second + localN

        return Location(globalE, globalN)
    }

    internal fun parseGridRefToLocation(ref: String, altitude: Double = 0.0): Location {
        val loc = parseGridReferenceInternal(ref)
        return Location(loc.x, loc.y, altitude)
    }
}
