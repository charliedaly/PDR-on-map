# PROJECT.md — PDR Diagnostic, Stride Calibration & Lag Profiling App

## 1. Overview
A lightweight, high-precision diagnostic Android application built in Kotlin and Jetpack Compose. The application profiles sensor latency, Extended Kalman Filter (EKF) convergence, Pedestrian Dead Reckoning (PDR) drift, and battery consumption under variable GPS polling frequencies (ranging from $1\text{ Hz}$ down to $0.01\text{ Hz}$ / once every 100 seconds).

The app operates entirely in an **Irish National Grid metric coordinate system** $(x, y)$ for all position, distance, and bearing computations. GPS bearings are transformed to grid North prior to fusion. On session launch, the EKF initializes its origin and state vector directly from an initial GPS fix.

The app runs an uninterrupted foreground service holding a partial wake lock, ingests hardware sensor inputs (Accelerometer, Gyroscope / Rotation Vector), processes measurements through an advanced 6-state EKF with self-calibrating stride length ($L$), compass bias ($b_\theta$), and path-aligned (Frenet frame) error decomposition, produces $1\text{ Hz}$ metric $(x, y)$ position updates, and renders live breadcrumbs and active route waypoints on an abstract dynamic map canvas with viewport auto-scaling.

## 2. Core Functional Requirements

### Metric Coordinate System & Grid Calibration
* **Internal Irish Metric Grid:** All location coordinates are stored and manipulated as metric Easting ($x$) and Northing ($y$) in meters. All distance and bearing calculations operate exclusively on this metric grid.
* **GPS Bearing Grid Alignment:** GPS bearings (true North) are converted to metric grid North before being processed in the PDR/EKF pipeline.
* **Initial GPS Fix EKF Initialization:** When PDR tracking is initiated, the service awaits/fetches an initial GPS fix to establish the EKF origin and initialize the state vector $X_0 = [x_0, y_0, 0, 0, L_0, b_{\theta, 0}]^T$.

### Permission & Lifecycle Management
* **Runtime Permissions:** Requests `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` (Android 13+ / API 33+), and `HIGH_SAMPLING_RATE_SENSORS` on startup. Directs users to app settings if permissions are denied and halts tracking if unresolved.
* **Foreground Service:** Runs a persistent foreground location service displaying an ongoing notification and holding a CPU `PartialWakeLock` to prevent system deep sleep during profiling sessions.

### Variable GPS Polling & 1 Hz EKF Engine
* **Dynamic GPS Polling:** Configures `FusedLocationProviderClient` with user-selected GPS update intervals from $1\text{ Hz}$ ($1,000\text{ ms}$) down to $0.01\text{ Hz}$ ($100,000\text{ ms}$).
* **1 Hz EKF Prediction Loop:** Generates estimated local Cartesian coordinates $(x, y)$ and state uncertainties every second ($1\text{ Hz}$ ticker) regardless of GPS fix availability.
* **6-State Vector:** Tracks state vector $X = [x, y, v_x, v_y, L, b_\theta]^T$, including dynamic stride length $L$ and compass bias $b_\theta$.
* **Path-Aligned Error Decomposition (Frenet Frame):** Rotates raw Cartesian GPS residuals ($\Delta x, \Delta y$) into longitudinal and lateral components, preserving state observability during sparse GPS intervals up to 100 seconds.

### Route, Waypoint & Leg Data Hierarchy
* **Metric Route Data Model:** Ingests CSV route files via `RouteParser`, converting waypoints into sequential `Leg` instances linked by `next` pointers.
* **Leg & Waypoint Processing:** Transfers `legDescription` from the start waypoint to the `Leg`, sets `waypoint.legDescription = null`, and deduplicates waypoints sharing identical metric positions while preserving leg sequence topology.
* **Enhanced Breadcrumbs:** Captures trajectory breadcrumbs with a `Location` (metric $x, y$, altitude), timestamp, source indicator (GPS vs. PDR), and snapshot copy of `EkfState`.

---

## 3. UI & Screen Specifications (Four Core Components)

1. **Header / Title Section:**
   * **Unloaded State:** Displays a primary option/button to load a CSV route file.
   * **Loaded State:** Displays the Route Title, number of legs, and total route length in kilometers/meters, while retaining an option to load a new Route file at any time.

2. **Telemetry Dashboard:**
   * Displays current 6-State EKF values in real-time:
     * **Pos (x, y)** with position error/variance placed underneath.
     * **Bearing** with heading error/variance placed underneath.
     * **Speed** with velocity error/variance placed underneath.
     * **Step / Stride Length ($L$)** with stride variance placed underneath.
     * **Compass Bias ($b_\theta$)** with bias variance placed underneath.

3. **Maximized Abstract Map Canvas:**
   * Maximized Compose Canvas auto-scaled to encompass all route waypoints and recorded breadcrumbs.
   * Renders route legs (lines), waypoints (labeled dots), and breadcrumbs (cyan for PDR, bright red for GPS fixes).
   * Verifies trajectory accuracy by confirming breadcrumb alignment inside route waypoints.

4. **Control Panel:**
   * Interactive control card featuring GPS polling rate selection chips ($1\text{ Hz}$ – $0.01\text{ Hz}$) and Start/Stop tracking service toggles.
