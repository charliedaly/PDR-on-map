# PROJECT.md — PDR Diagnostic, Stride Calibration & Navigation Engine App

## 1. Overview
A lightweight, high-precision diagnostic Android application built in Kotlin and Jetpack Compose. The application profiles sensor latency, Extended Kalman Filter (EKF) convergence, Pedestrian Dead Reckoning (PDR) drift, battery consumption under variable GPS polling frequencies (ranging from $1\text{ Hz}$ down to $0.1\text{ Hz}$ / once every 10 seconds), and real-time audio navigation assistance via an independent `Hike` state machine.

The app operates entirely in an **Irish National Grid metric coordinate system** $(x, y)$ for all position, distance, and bearing computations. GPS bearings are transformed to grid North prior to fusion. On session launch, the EKF initializes its origin and state vector directly from an initial GPS fix.

The app runs an uninterrupted foreground service holding a partial wake lock, ingests hardware sensor inputs (Accelerometer, Gyroscope / Rotation Vector), processes measurements through an advanced 6-state EKF with self-calibrating stride length ($L$), compass bias ($b_\theta$), and path-aligned error decomposition, produces $1\text{ Hz}$ metric $(x, y)$ position updates, and renders live breadcrumbs, active route waypoints, customizable Irish Grid overlays, and offline OpenStreetMap (OSM) background tiles on an abstract dynamic map canvas with viewport auto-scaling.

## 2. Core Functional Requirements

### Metric Coordinate System & Grid Calibration
* **Internal Irish Metric Grid:** All location coordinates are stored and manipulated as metric Easting ($x$) and Northing ($y$) in meters. All distance (`distanceTo`) and bearing (`bearingTo`) calculations operate exclusively on this metric grid.
* **EPSG:1641 / EPSG:1753 Bursa-Wolf Helmert Transformation:** Converts between Ireland 1965 (TM65 / Airy 1830 Modified ellipsoid) and WGS84 / ETRS89 ellipsoids using the standard 7-parameter Position Vector Helmert transformation matrix.
* **Sub-Meter 10km Grid Offsets & 2D Bilinear Interpolation:** Loads local grid shift corrections from `irish_grid_offsets_10km.json` and applies 2D Bilinear Interpolation across 10km grid node corners ($c_{00}, c_{10}, c_{01}, c_{11}$) to achieve sub-meter ($\le 0.10\text{m}$) coordinate conversion accuracy across Ireland.
* **Initial GPS Fix EKF Initialization:** When PDR tracking is initiated, the service fetches an initial GPS fix to anchor the grid origin and initialize the state vector $X_0 = [x_0, y_0, 0, 0, L_0, b_{\theta, 0}]^T$.

### Independent `Hike` Navigation Engine & Configuration (`HikeConfig`)
* **Session Lifecycle:** An independent `Hike` instance comes into existence when tracking starts (`HikeRepository.startHike`). When tracking stops (`HikeRepository.stopHike`), background tracking ceases while the hike state and trajectory are preserved until tracking is started again.
* **Configurable Thresholds (`HikeConfig`):** All navigation magic numbers (arrival threshold, approach threshold, off-course heading deviation, minimum speed, drift cool-off, and steady cool-off) are encapsulated in `HikeConfig` and fully adjustable via the 5-Tab Settings dialog.
* **Priority-Ordered Leg Evaluation (`LegState`):** Evaluates progress on the current route leg in strict priority order:
  1. `TARGET_REACHED` — Distance to target $< \text{arriveThresholdMeters}$ (default $10\text{m}$).
  2. `HIKE_ENDED` — Reached final waypoint of the last leg on the route.
  3. `TOO_FAR` — Hiker has walked past the target along the leg vector ($t > 1.05$).
  4. `APPROACHING_TARGET` — Distance to target $< \text{approachThresholdMeters}$ (default $40\text{m}$).
  5. `LEG_START` — Leaving the start waypoint.
  6. `OFFCOURSE` — Heading/bearing deviates by $> 10^\circ$ (accounting for 2-sigma EKF heading variance).
  7. `OK` — On track; no announcement needed.
* **TTS Queueing & Pruning:** Maintains a synchronized TTS message queue (`TtsQueueItem`). Outdated `OFFCOURSE` messages are automatically purged and replaced by the newest off-course correction, ensuring fresh prompts are spoken sequentially without overlap.

### Route Editor (`RouteEditorScreen`)
* **Interactive Waypoint Editor:** Tapping the pencil icon (`✎`) in the leg viewport opens a dedicated route editing page (`RouteEditorScreen`).
* **Spreadsheet Layout:** Displays each waypoint's name, Easting ($x$), Northing ($y$), and altitude in editable text fields.
* **List Manipulation Actions:**
  - **Return Arrow (`←`)**: Saves modifications, rebuilds route legs, and returns to the main screen as the active hike route.
  - **Reverse List (`↕`)**: Reverses the order of waypoints.
  - **Move Up / Down (`↑`, `↓`)**: Reorders waypoints in the sequence.
  - **Insert (`+`)**: Inserts a new waypoint after the current one.
  - **Delete (`🗑`)**: Removes a waypoint from the route.

### Offline OpenStreetMap (OSM) Tiles & Full Persistence (`AppPreferences`)
* **Offline OSM Map Background (`OsmTileManager`):** Renders OpenStreetMap Slippy Map tiles as an offline background layer. Tile bounding box corners are converted to metric Irish Grid $(x, y)$ coordinates and mapped through `toCanvasOffset`, ensuring background tiles seamlessly align with the metric grid in both Map View and Leg View (rotated).
* **Programmatic Tile Downloader:** Programmatically fetches and caches PNG tiles for offline operation:
  - **Ireland Base Map:** Programmatically downloads zooms 8–14 for the entire island of Ireland.
  - **Route Area Tiles:** Programmatically downloads high-resolution zooms 10–18 along the active route bounding box.
* **Full State & Tab Persistence (`AppPreferences`):** Automatically saves and restores across app restarts:
  - View mode (`MapConfig`: Map View vs. Leg View, 1km grid, 100m grid, telemetry dashboard visibility, offline OSM background toggle, persistent GPS polling interval).
  - Breadcrumb settings (`BreadcrumbConfig`: trail visibility, dot diameter, color mode, age fading, perimeter accuracy circles, stationary rest stops).
  - Navigation settings (`HikeConfig`: thresholds and cool-off intervals).
  - Last selected Settings Tab (`settings_selected_tab`).
  - Previously loaded course (route name, CSV content, source URI).

---

## 3. UI & Screen Specifications

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

4. **Maximized Abstract Map Canvas (`MapScreen`):**
   * Maximized Compose Canvas auto-scaled to encompass all route waypoints, active leg, and recorded breadcrumbs.
   * **Smart Waypoint Label Placement:** Calculates the outer bisector direction vector (opposite the acute angle formed by connected legs) and aligns waypoint names/altitudes into 4 cardinal quadrants with explicit circle clearance. Uses translucent black backgrounds (`0.20f` alpha) to prevent obstructing route lines.
   * **Breadcrumb Styling & Trails:**
     - **Connected Color Line Segments:** Connects consecutive breadcrumbs with line segments (`strokeWidth = 1.0f`) using gradient color/alpha transitions matching breadcrumb points.
     - **Color Coding Modes:** Supports `GPS_PDR` (Red for GPS fixes, Blue for PDR steps), `SPEED` (dynamic gradient between min and max walking speeds), and `STRIDE` (gradient between min and max stride lengths). Speed calculations fall back to physical distance/time deltas ($\Delta d / \Delta t$) between points to eliminate EKF startup filter convergence delay.
     - **Smooth Age Fading:** Scales alpha linearly from $0.2f$ (20% opacity for oldest breadcrumbs) to $1.0f$ (solid for newest breadcrumbs).
     - **Perimeter Accuracy Circles:** Renders position variance as a subtle stroke outline (`Stroke(width = 1.5f)`).
     - **Stationary Rest Stops:** Renders rest stop circles with duration labels when stationary $> 30\text{s}$.
     - **Dynamic Color Legend Card:** Displays min/max values and gradient bars for Speed and Stride modes, cleanly handling single-value tracks.
   * **Control Overlays:** Upper-right Compass Button (toggling Map View / Leg View) and relative bearing indicator; lower-right "Fit All" view recentering and Settings gear button (`⚙`).

5. **Five-Tab Settings Dialog Overhaul:**
   * **Map Tab:** Toggle Leg View, 1km grid, 100m grid, waypoint names/heights, telemetry dashboard, and offline OSM background.
   * **Breadcrumbs Tab:** Toggle trail visibility, dot diameter slider, color mode selection (`GPS_PDR`, `SPEED`, `STRIDE`), age fading, perimeter accuracy circles, and stationary stops.
   * **Audio Tab:** Toggle TTS audio, adjust arrival/approach thresholds, off-course deviation, cool-off intervals, and speech rate slider.
   * **Navigation Tab:** Offline Ireland tile downloader, route area tile downloader, and GPS Polling Interval selection chips (1s, 2s, 5s, 10s) with active selection highlighting.
   * **Dev Tab:** Telemetry recording, CSV track loading, and diagnostic controls.
