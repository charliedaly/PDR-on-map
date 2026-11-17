# PDR Diagnostic App Implementation Blueprint (Implementation.md)

## 1. Core Architecture & EKF Discrete State Space Formulation

### `DiagnosticEkf` Engine & Metric Irish Grid Formulation
- **Internal Metric Grid System:** All position computations use metric Easting ($x$) and Northing ($y$) in meters via Irish Grid transformations (`CoordinateUtils`).
- **Bursa-Wolf 7-Parameter Helmert Transformation:**
  Converts between Ireland 1965 (TM65 / Airy 1830 Modified) and WGS84 / ETRS89 ellipsoids using standard ECEF Position Vector parameters ($dX = +482.530\text{m}, dY = -130.596\text{m}, dZ = +564.557\text{m}, s = +8.15\text{ ppm}, r_x = -1.042'', r_y = -0.214'', r_z = +0.631''$).
- **Sub-Meter 2D Bilinear Interpolation:**
  Loads 10km grid offsets from `irish_grid_offsets_10km.json` and performs 2D bilinear interpolation across 10km grid node corners ($c_{00}, c_{10}, c_{01}, c_{11}$) to achieve sub-meter ($\le 0.10\text{m}$) coordinate precision across Ireland.
- **GPS Bearing Conversion:** GPS bearings (true North) are converted to grid North prior to EKF measurement updates.
- **Initial Fix EKF Initialization:** Upon tracking startup, the service obtains an initial GPS fix to anchor the grid origin and set initial state $X_0 = [x_{\text{gps}}, y_{\text{gps}}, 0, 0, L_0, b_{\theta, 0}]^T$.

- **State Vector ($X$):** Expanded 6-state vector $X = [x, y, v_x, v_y, L, b_\theta]^T$
  - $x, y$: Irish metric grid coordinates (meters)
  - $v_x, v_y$: Linear velocity components (m/s)
  - $L$: Dynamic stride length (meters, initialized to $\approx 0.75\text{ m}$)
  - $b_\theta$: Compass bias / heading error (radians, initialized to $0.0\text{ rad}$)

- **1 Hz Prediction Step (State Transition):**
  Executed on a $1\text{ Hz}$ ticker coroutine in `PdrTrackingService`:
  $$X_k^- = F X_{k-1}$$
  $$P_k^- = F P_{k-1} F^T + Q$$
  where $F$ is the state transition matrix ($\Delta t = 1.0\text{ s}$) and $Q = \text{diag}(\sigma_x^2, \sigma_y^2, \sigma_{vx}^2, \sigma_{vy}^2, \sigma_L^2, \sigma_{b_\theta}^2)$.

---

## 2. Independent `Hike` Navigation Engine & Configurations (`HikeConfig`, `MapConfig`, `BreadcrumbConfig`)

### Class Responsibilities & Lifecycle Management
- **`Hike(val route: Route?, val config: HikeConfig)`**: Independent domain class managing active route leg evaluation, trajectory breadcrumbs, and TTS navigation string generation.
- **`HikeConfig`**: Encapsulates all navigation magic numbers:
  - `arriveThresholdMeters` ($10.0\text{m}$)
  - `approachThresholdMeters` ($40.0\text{m}$)
  - `offCourseHeadingDevDeg` ($10.0^\circ$)
  - `offCourseMinSpeedMps` ($0.4\text{ m/s}$)
  - `driftCoolOffSec` ($4\text{s}$)
  - `steadyCoolOffSec` ($6\text{s}$)
  - `speechRate` ($1.0f$)
- **`MapConfig`**: Encapsulates map view states (`isLegView`, `show1kmGrid`, `show100mGrid`, `showWaypointNames`, `showWaypointHeights`, `gpsPollingIntervalMs`, `showTelemetryViewport`, `showOsmBackground`).
- **`BreadcrumbConfig`**: Encapsulates breadcrumb styling & trail options:
  - `showBreadcrumbs` (`true`)
  - `dotDiameterPx` (`3.0f`)
  - `colorMode` (`GPS_PDR`, `SPEED`, `STRIDE`)
  - `speedStartColor` / `speedEndColor` (Green to Red gradient)
  - `strideStartColor` / `strideEndColor` (Cyan to Gold gradient)
  - `fadeWithAge` (`false`, opacity ramps from 20% / 0.2f for oldest to 100% / 1.0f for newest)
  - `showAccuracyCircles` (`true`, stroke perimeter outline `1.5f`)
  - `showStationaryStops` (`true`, stationary $> 30\text{s}$)
- **`AppPreferences`**: Manages `SharedPreferences` persistence across app restarts for `MapConfig`, `HikeConfig`, `BreadcrumbConfig`, `settings_selected_tab`, and last loaded route CSV content.

### TTS Queueing & Stale `OFFCOURSE` Pruning
- **`pendingTtsQueue`**: Synchronized queue holding `TtsQueueItem` objects.
- **Sequential Playback**: `TtsManager` triggers `onDone` callbacks when speech finishes, advancing to the next queued item.
- **Stale Pruning**: When a new `LegState.OFFCOURSE` message arrives, any pending unplayed `OFFCOURSE` messages in the queue are removed (`pendingTtsQueue.removeAll { it.state == LegState.OFFCOURSE }`), ensuring only the latest course correction is spoken.

---

## 3. Domain Data Models & Route Hierarchy (`com.example.irishhike.model`)

- **`Location`**:
  `open class Location(open val x: Double, open val y: Double, open val altitude: Double = 0.0)`
  Provides `distanceTo(other: Location)` and `bearingTo(other: Location)` delegating to `CoordinateUtils`.

- **`Waypoint`**:
  `class Waypoint(override val x: Double, override val y: Double, override val altitude: Double = 0.0, val name: String, val description: String = "", var legDescription: String? = null, val arrive: Double = 10.0, val approach: Double = 40.0) : Location(x, y, altitude)`

- **`Leg`**:
  `class Leg(val start: Waypoint, val target: Waypoint, var next: Leg? = null, var legDescription: String? = null)`

- **`Route`**:
  `class Route(val name: String, val waypoints: List<Waypoint>, val legs: List<Leg>)`

- **`Breadcrumb`**:
  `data class Breadcrumb(val id: Long, val location: Location, val timestampMs: Long, val isGpsFix: Boolean = false, val ekfState: EkfState? = null)`

---

## 4. Offline Map Tiles, Track Preservation & UI Canvas Integration

- **`OsmTileManager`**:
  Converts OSM Slippy Map tile bounding boxes $(z, x, y)$ to metric Irish National Grid bounds $(x, y)$, loading PNG bitmaps from local storage (`files/tiles/{z}/{x}/{y}.png`) or assets (`assets/tiles/`). Includes `downloadIrelandTiles(...)` (zooms 8–14) and `downloadRouteAreaTiles(...)` (zooms 10–18).
- **Canvas Integration (`MapScreen`)**:
  - Tiles are drawn via `drawImage` using `toCanvasOffset`, automatically matching the metric grid scale, translation, and rotation (in both Map View and Leg View).
  - **Connecting Line Segments:** Connects consecutive breadcrumb points with individual line segments (`strokeWidth = 1.0f`) using `Brush.linearGradient` matching breadcrumbs' exact colors and fading age opacity.
  - **Robust Speed Evaluation (`getBreadcrumbSpeed`)**: Evaluates speed from EKF state or computes physical distance/time delta ($\Delta d / \Delta t$) between adjacent breadcrumbs to eliminate startup filter convergence artifacts.
  - **Smart Waypoint Label Placement:** Computes the outer bisector direction vector (opposite the acute angle formed by connected legs) and aligns waypoint names/altitudes into 4 cardinal quadrants with explicit circle clearance. Uses translucent black backgrounds (`0.20f` alpha) to prevent obstructing route lines.
- **Track Export & GPX Parsing**:
  - `HikerTrackExporter`: Exports `<is_gps_fix>` tag in GPX `<extensions>` block for each `<trkpt>`.
  - `GpxTrackParser`: Parses `<is_gps_fix>` from GPX extensions, preserving GPS fix (`isGpsFix = true`, Red) vs PDR step (`isGpsFix = false`, Blue) distinction when re-importing breadcrumb tracks.
- **`RouteEditorScreen`**:
  Opened via the pencil icon (`✎`) in `HikeNavigationPanel`. Provides a spreadsheet row layout for Waypoint name, Easting ($x$), Northing ($y$), and altitude, with in-place controls for reordering, inserting, and deleting waypoints.
