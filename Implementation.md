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

## 2. Path-Aligned (Frenet Frame) GPS Measurement Update

- **Geodetic to Metric Projection (`CoordinateUtils`):**
  Uses `CoordinateUtils.latLonToMetric(lat, lon)` to convert raw GPS fix $(lat, lon)$ into metric `Location(x, y, altitude)`.

- **Frenet Frame Residual Rotation:**
  Residuals $\Delta x = x_{\text{gps}} - x_{\text{ekf}}$, $\Delta y = y_{\text{gps}} - y_{\text{ekf}}$ rotated into heading $\theta$:
  $$\Delta e_{\text{longitudinal}} = \Delta x \cdot \sin(\theta) + \Delta y \cdot \cos(\theta)$$
  $$\Delta e_{\text{lateral}} = \Delta x \cdot \cos(\theta) - \Delta y \cdot \sin(\theta)$$

- **State Correction Routing:**
  $\Delta e_{\text{longitudinal}}$ drives updates primarily to Stride Length ($L$), while $\Delta e_{\text{lateral}}$ drives Compass Bias ($b_\theta$). Updates emit a `Breadcrumb` containing metric `Location`, timestamp, source indicator, and `EkfState` snapshot.

---

## 3. Domain Data Models & Route Hierarchy (`com.example.pdrandroutetest.model`)

- **`Location`**:
  `open class Location(open val x: Double, open val y: Double, open val altitude: Double = 0.0)`
  Provides `getDistance(other: Location)` and `getBearing(other: Location)` delegating to `CoordinateUtils`.

- **`Waypoint`**:
  `class Waypoint(override val x: Double, override val y: Double, override val altitude: Double = 0.0, val name: String, val description: String = "", var legDescription: String? = null) : Location(x, y, altitude)`

- **`Leg`**:
  `class Leg(val start: Waypoint, val target: Waypoint, var next: Leg? = null, var legDescription: String? = null) { fun getNextLeg(): Leg? = next }`

- **`Route`**:
  `class Route(val name: String, val waypoints: List<Waypoint>, val legs: List<Leg>)`
  - Construction logic parses CSV waypoints via `RouteParser`, wires sequential `Leg` instances (`leg[i].next = leg[i+1]`), transfers `legDescription` to `Leg`, clears `waypoint.legDescription = null`, and deduplicates `waypoints` by position (`x`, `y`). Computes `totalLengthMeters`.

- **`Breadcrumb`**:
  `data class Breadcrumb(val id: Long, val location: Location, val timestampMs: Long, val isGpsFix: Boolean = false, val ekfState: EkfState? = null)`

---

## 4. Encapsulated `CoordinateUtils` API

Refactored to keep conversion internals private, exposing only 4 public methods:
- `fun latLonToMetric(lat: Double, lon: Double): Location`
- `fun metricToLatLon(location: Location): Pair<Double, Double>`
- `fun getDistance(loc1: Location, loc2: Location): Double`
- `fun getBearing(loc1: Location, loc2: Location): Double`

---

## 5. UI Implementation (Four Components)

1. **Header / Title Component (`RouteHeader` / `TopAppBar`):**
   - File picker launcher allowing CSV route file loading.
   - Shows Route Name, Leg Count, and Total Route Distance when loaded.
2. **Telemetry Dashboard Component (`TelemetryDashboard`):**
   - 5 metrics: Pos (x, y), Bearing, Speed, Step Length, Compass Bias — each with its error/variance displayed underneath.
3. **Maximized Map Canvas (`MapScreen`):**
   - Auto-scales canvas viewport to show all route waypoints, legs, and recorded breadcrumbs.
4. **Control Panel (`ControlPanel`):**
   - GPS Polling rate toggles ($1\text{ Hz}$ to $0.01\text{ Hz}$) and Start/Stop tracking service button.
