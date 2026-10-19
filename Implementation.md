# PDR Diagnostic App Implementation Blueprint (Implementation.md)

## 1. Core Architecture & EKF Discrete State Space Formulation

### `DiagnosticEkf` Engine & Metric Irish Grid Formulation
- **Internal Metric Grid System:** All position computations use metric Easting ($x$) and Northing ($y$) in meters via Irish Grid transformations (`CoordinateUtils`).
- **GPS Bearing Conversion:** GPS bearings (true North) are converted to grid North prior to EKF measurement updates.
- **Initial Fix EKF Initialization:** Upon tracking startup, the service obtains an initial GPS fix to anchor the grid origin and set initial state $X_0 = [x_{\text{gps}}, y_{\text{gps}}, 0, 0, L_0, b_{\theta, 0}]^T$.

- **State Vector ($X$):** Expanded 6-state vector $X = [x, y, v_x, v_y, L, b_\theta]^T$
  - $x, y$: Irish metric grid coordinates (meters)
  - $v_x, v_y$: Linear velocity components (m/s)
  - $L$: Dynamic stride length (meters, initialized to $\approx 0.75\text{ m}$)
  - $b_\theta$: Compass bias / heading error (radians, initialized to $0.0\text{ rad}$)

- **1 Hz Prediction Step (State Transition):**
  Executed on a $1\text{ Hz}$ ticker coroutine:
  $$X_k^- = F X_{k-1}$$
  $$P_k^- = F P_{k-1} F^T + Q$$
  where $F$ is the state transition matrix ($\Delta t = 1.0\text{ s}$) and $Q = \text{diag}(\sigma_x^2, \sigma_y^2, \sigma_{vx}^2, \sigma_{vy}^2, \sigma_L^2, \sigma_{b_\theta}^2)$.

---

## 2. Independent `Hike` Navigation Engine & Configurations (`HikeConfig`, `MapConfig`)

### Class Responsibilities & Lifecycle Management
- **`Hike(val route: Route?, val config: HikeConfig)`**: Independent domain class managing active route leg evaluation, trajectory breadcrumbs, and TTS navigation string generation.
- **`HikeConfig`**: Encapsulates all navigation magic numbers:
  - `arriveThresholdMeters` ($10.0\text{m}$)
  - `approachThresholdMeters` ($40.0\text{m}$)
  - `offCourseHeadingDevDeg` ($10.0^\circ$)
  - `offCourseMinSpeedMps` ($0.4\text{ m/s}$)
  - `driftCoolOffSec` ($4\text{s}$)
  - `steadyCoolOffSec` ($6\text{s}$)
- **`MapConfig`**: Encapsulates map view states (`isLegView`, `show1kmGrid`, `show100mGrid`, `showTelemetryViewport`, `gpsPollingIntervalMs`, `showOsmBackground`).
- **`AppPreferences`**: Manages SharedPreferences persistence across app restarts for `MapConfig`, `HikeConfig`, and last loaded course CSV content.

### TTS Queueing & Stale `OFFCOURSE` Pruning
- **`pendingTtsQueue`**: Synchronized queue holding `TtsQueueItem` objects.
- **Sequential Playback**: `TtsManager` triggers `onDone` callbacks when speech finishes, advancing to the next queued item.
- **Stale Pruning**: When a new `LegState.OFFCOURSE` message arrives, any pending unplayed `OFFCOURSE` messages in the queue are removed (`pendingTtsQueue.removeAll { it.state == LegState.OFFCOURSE }`), ensuring only the latest course correction is spoken.

---

## 3. Domain Data Models & Route Hierarchy (`com.example.pdrandroutetest.model`)

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

## 4. Offline Map Tiles & UI Canvas Integration

- **`OsmTileManager`**:
  Converts OSM Slippy Map tile bounding boxes $(z, x, y)$ to metric Irish National Grid bounds $(x, y)$, loading PNG bitmaps from local storage (`files/tiles/{z}/{x}/{y}.png`) or assets (`assets/tiles/`). Includes `downloadIrelandTiles(...)` for downloading tiles for Ireland.
- **Canvas Integration**:
  Tiles are drawn via `drawImage` using `toCanvasOffset`, automatically matching the metric grid scale, translation, and rotation (in both Map View and Leg View) without requiring any changes to existing drawing logic. Toggleable via `showOsmBackground` in Map Settings.
- **`RouteEditorScreen`**:
  Opened via the pencil icon (`✎`) in `HikeNavigationPanel`. Provides a spreadsheet row layout for Waypoint name, Easting ($x$), Northing ($y$), and altitude, with in-place controls for reordering, inserting, and deleting waypoints.
