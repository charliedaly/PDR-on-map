# PROJECT.md — PDR Diagnostic, Stride Calibration & Navigation Engine App

## 1. Overview
A lightweight, high-precision diagnostic Android application built in Kotlin and Jetpack Compose. The application profiles sensor latency, Extended Kalman Filter (EKF) convergence, Pedestrian Dead Reckoning (PDR) drift, battery consumption under variable GPS polling frequencies (ranging from $1\text{ Hz}$ down to $0.01\text{ Hz}$ / once every 100 seconds), and real-time audio navigation assistance via an independent `Hike` state machine.

The app operates entirely in an **Irish National Grid metric coordinate system** $(x, y)$ for all position, distance, and bearing computations. GPS bearings are transformed to grid North prior to fusion. On session launch, the EKF initializes its origin and state vector directly from an initial GPS fix.

The app runs an uninterrupted foreground service holding a partial wake lock, ingests hardware sensor inputs (Accelerometer, Gyroscope / Rotation Vector), processes measurements through an advanced 6-state EKF with self-calibrating stride length ($L$), compass bias ($b_\theta$), and path-aligned (Frenet frame) error decomposition, produces $1\text{ Hz}$ metric $(x, y)$ position updates, and renders live breadcrumbs, active route waypoints, and customizable Irish Grid overlays on an abstract dynamic map canvas with viewport auto-scaling.

## 2. Core Functional Requirements

### Metric Coordinate System & Grid Calibration
* **Internal Irish Metric Grid:** All location coordinates are stored and manipulated as metric Easting ($x$) and Northing ($y$) in meters. All distance (`distanceTo`) and bearing (`bearingTo`) calculations operate exclusively on this metric grid.
* **GPS Bearing Grid Alignment:** GPS bearings (true North) are converted to metric grid North before being processed in the PDR/EKF pipeline.
* **Initial GPS Fix EKF Initialization:** When PDR tracking is initiated, the service awaits/fetches an initial GPS fix to establish the EKF origin and initialize the state vector $X_0 = [x_0, y_0, 0, 0, L_0, b_{\theta, 0}]^T$.

### Independent `Hike` Navigation Engine & Configuration (`HikeConfig`)
* **Session Lifecycle:** An independent `Hike` instance comes into existence when tracking starts (`HikeRepository.startHike`). When tracking stops (`HikeRepository.stopHike`), all background activity ceases while the hike state and trajectory are preserved until tracking is started again.
* **Configurable Thresholds (`HikeConfig`):** All navigation magic numbers (arrival threshold, approach threshold, off-course heading deviation, minimum speed, drift cool-off, and steady cool-off) are encapsulated in `HikeConfig` and fully adjustable via the Map Settings dialog.
* **Priority-Ordered Leg Evaluation (`LegState`):** Evaluates progress on the current route leg in strict priority order:
  1. `TARGET_REACHED` — Distance to target $< \text{arriveThresholdMeters}$ (default $10\text{m}$).
  2. `HIKE_ENDED` — Reached final waypoint of the last leg on the route.
  3. `TOO_FAR` — Hiker has walked past the target along the leg vector ($t > 1.05$).
  4. `APPROACHING_TARGET` — Distance to target $< \text{approachThresholdMeters}$ (default $40\text{m}$).
  5. `LEG_START` — Leaving the start waypoint.
  6. `OFFCOURSE` — Heading/bearing deviates by $> 10^\circ$ (accounting for 2-sigma EKF heading variance).
  7. `OK` — On track; no announcement needed.
* **TTS Queueing & Pruning:** Maintains a synchronized TTS message queue (`TtsQueueItem`). Outdated `OFFCOURSE` messages are automatically purged and replaced by the newest off-course correction, ensuring only fresh prompts are spoken sequentially without overlap.

### Route Editor (`RouteEditorScreen`)
* **Interactive Waypoint Editor:** Tapping the pencil icon (`✎`) in the leg viewport opens a dedicated route editing page (`RouteEditorScreen`).
* **Spreadsheet Layout:** Displays each waypoint's name, Easting ($x$), Northing ($y$), and altitude in editable text fields.
* **List Manipulation Actions:**
  - **Return Arrow (`←`)**: Saves modifications, rebuilds route legs, and returns to the main screen as the active hike route.
  - **Reverse List (`↕`)**: Reverses the order of waypoints.
  - **Move Up / Down (`↑`, `↓`)**: Reorders waypoints in the sequence.
  - **Insert (`+`)**: Inserts a new waypoint after the current one.
  - **Delete (`🗑`)**: Removes a waypoint from the route.

### Persistence (`AppPreferences`) & Custom Map Canvas
* **Full State Persistence (`AppPreferences`):** Automatically saves and restores across app restarts:
  - View mode (`MapConfig`: Map View vs. Leg View, 1km grid, 100m grid, telemetry dashboard visibility).
  - Navigation settings (`HikeConfig`: thresholds and cool-off intervals).
  - Previously loaded course (route name and CSV content).
* **Rotated Grid Overlays:** 1km labeled grid lines and 100m unlabeled sub-grid lines are fully visible in both Map View and Leg View (correctly rotated along the grid axis).
* **Intelligent Waypoint Name Placement:** Renders waypoint names using angular bisector placement relative to connected leg vectors to avoid crossing leg lines.
* **Animated Leg Transitions:** Animates leg changes in Leg View over 2 seconds (20 steps) with dynamic scale rules.

---

## 3. UI & Screen Specifications (Five Core Components)

1. **Header / Title Section:**
   * **Unloaded State:** Displays an option to load a CSV route file or sample route.
   * **Loaded State:** Displays Route Title, number of legs, and total route length in kilometers/meters.

2. **Hike Navigation Panel (`HikeNavigationPanel`):**
   * Located directly above the telemetry viewport.
   * **Line 1:** Leg label and three icon-only buttons (Left Arrow to move leg back, Right Arrow to move leg forward, and Pencil to open `RouteEditorScreen`).
   * **Line 2:** Current leg summary (`StartName ➔ Bearing° • Lengthm ➔ TargetName`).
   * **Line 3:** Hiker telemetry (`Hiker: (Grid) Bearing° Distance m Speedm/s`).

3. **Telemetry Dashboard:**
   * Displays current 6-State EKF values in real-time (Position, Bearing, Speed, Stride Length $L$, Compass Bias $b_\theta$). Toggleable via Map Settings.

4. **Maximized Abstract Map Canvas:**
   * Maximized Compose Canvas auto-scaled to encompass all route waypoints, active leg, and recorded breadcrumbs.
   * Upper-right controls include the interactive Compass Button (toggling Map View / Leg View) and relative bearing indicator.
   * Lower-right controls include "Fit All" view recentering and the Settings gear button (`⚙`) for comprehensive grid overlays and navigation threshold configurations.
