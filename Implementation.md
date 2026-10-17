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

- **PDR Step Integration:**
  When a step event occurs at measured heading $\theta_{\text{raw}}$:
  $$v_x = \frac{L \cdot \sin(\theta_{\text{raw}} - b_\theta)}{\Delta t_{\text{step}}}, \quad v_y = \frac{L \cdot \cos(\theta_{\text{raw}} - b_\theta)}{\Delta t_{\text{step}}}$$

---

## 2. Independent `Hike` Navigation Engine (`com.example.pdrandroutetest.hike`)

### Class Responsibilities & Lifecycle Management
- **`Hike(val route: Route?)`**: Independent domain class managing active route leg evaluation, trajectory breadcrumbs, and TTS navigation string generation.
- **Session Lifecycle**: Instantiated via `HikeRepository.startHike(route)` on tracking startup. Stopped via `HikeRepository.stopHike()` on tracking stop, freezing state until a new tracking session replaces it.
- **`LegAnnouncementTracker`**: Stored per leg to track one-time announcement booleans (`announcedLegStart`, `announcedApproaching`, `announcedTooFar`, `announcedReached`, `announcedHikeEnded`).

### Relative Bearing Correction
- **`relativeBearingCorrection(location, ekfState)`**: Computes the relative bearing offset between current heading and required bearing to `leg.target` in range $[-180.0, +180.0]$.
  - $+15.0^\circ$: Facing 15 degrees too far left (correction: 15 degrees to the right).
  - $-30.0^\circ$: Facing 30 degrees too far right (correction: -30 degrees).
- **`bearingCorrectionString(location, ekfState)`**: Formats the correction as `"plus X degrees"` or `"minus X degrees"`.

### Evaluation Priority Order (`evaluateLegState`)
1. **`TARGET_REACHED`**: `if (!tracker.announcedReached)` $\rightarrow$ `distanceToTarget < leg.target.arrive` (15m).
2. **`HIKE_ENDED`**: `if (leg.next == null)` on final target arrival.
3. **`TOO_FAR`**: `if (!tracker.announcedTooFar)` $\rightarrow$ `isPastTarget(location, leg)` ($t > 1.05$ along leg vector).
4. **`APPROACHING_TARGET`**: `if (!tracker.announcedApproaching)` $\rightarrow$ `distanceToTarget < leg.target.approach` (100m).
5. **`LEG_START`**: `if (!tracker.announcedLegStart)` $\rightarrow$ `distanceFromStart > leg.start.arrive` (15m). Suppressed if `announcedReached`, `announcedHikeEnded`, `announcedTooFar`, or `announcedApproaching` has already occurred on short legs.
6. **`OFFCOURSE`**: `bearingOffset > 10.0^\circ` relative to `location.bearingTo(leg.target)`. Respects configurable cool-off thresholds (`driftCoolOffSec`, default 2s; `steadyCoolOffSec`, default 15s).
7. **`OK`**: Default when on track; produces `null` message.

---

## 3. Domain Data Models & Route Hierarchy (`com.example.pdrandroutetest.model`)

- **`Location`**:
  `open class Location(open val x: Double, open val y: Double, open val altitude: Double = 0.0)`
  Provides `distanceTo(other: Location)` and `bearingTo(other: Location)` delegating to `CoordinateUtils`.

- **`Waypoint`**:
  `class Waypoint(override val x: Double, override val y: Double, override val altitude: Double = 0.0, val name: String, val description: String = "", var legDescription: String? = null, val arrive: Double = 15.0, val approach: Double = 100.0) : Location(x, y, altitude)`

- **`Leg`**:
  `class Leg(val start: Waypoint, val target: Waypoint, var next: Leg? = null, var legDescription: String? = null) { fun getNextLeg(): Leg? = next }`

- **`Route`**:
  `class Route(val name: String, val waypoints: List<Waypoint>, val legs: List<Leg>)`

- **`Breadcrumb`**:
  `data class Breadcrumb(val id: Long, val location: Location, val timestampMs: Long, val isGpsFix: Boolean = false, val ekfState: EkfState? = null)`

---

## 4. Telemetry Playback Engine & Custom Irish Grid Canvas

- **`TelemetryPlaybackEngine`**:
  Supports `stepForward()` and `stepBack()` suspend calls synchronized with a `Mutex`. Step back resets the EKF and re-executes trajectory up to the previous index for instant, accurate trajectory updates.
- **Intelligent Waypoint Name Placement**:
  Calculates the largest angular gap between attached route leg vectors for each waypoint and positions the label along the gap bisector, preventing text from crossing leg lines.
- **Animated Leg Transitions**:
  Executes a 20-step, 2-second interpolation when advancing legs in Leg View. Interpolates leg start/target positions and applies scale rules (preserving scale for shorter legs or $<75\%$ viewport height; scaling so new leg matches $75\%$ height).
- **Interactive Compass Button & Grid Overlays (`MapScreen`)**:
  - Upper-right Compass Button (`Box` with `.clickable`) toggles between Map View and Leg View mode on click.
  - Lower-right gear icon button (`⚙`) launches Map Settings dialog.
  - **1km Grid Overlay**: Draws 1km gridlines with 2-digit Irish National Grid Easting/Northing labels (`%02d`).
  - **100m Grid Overlay**: Draws subtle, unlabeled 100m sub-grid lines.
  - **Off-Course Cooldown Controls**: Configurable drift ($1\text{s}-5\text{s}$) and steady ($5\text{s}-30\text{s}$) cool-off chips.
