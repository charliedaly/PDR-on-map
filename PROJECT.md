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

### Independent `Hike` Navigation Engine
* **Session Lifecycle:** An independent `Hike` instance comes into existence when tracking starts (`HikeRepository.startHike`). When tracking stops (`HikeRepository.stopHike`), all background activity ceases while the hike state and trajectory are preserved until tracking is started again (at which point the old state is discarded for a new `Hike`).
* **Priority-Ordered Leg Evaluation (`LegState`):** Evaluates progress on the current route leg in strict priority order:
  1. `TARGET_REACHED` — Distance to target $< \text{leg.target.arrive}$ (15m threshold).
  2. `HIKE_ENDED` — Reached final waypoint of the last leg on the route.
  3. `TOO_FAR` — Hiker has walked past the target along the leg vector ($t > 1.05$).
  4. `APPROACHING_TARGET` — Distance to target $< \text{leg.target.approach}$ (100m threshold).
  5. `LEG_START` — Leaving the start waypoint ($> 15\text{m}$ from start waypoint). Suppressed if `TARGET_REACHED`, `HIKE_ENDED`, `TOO_FAR`, or `APPROACHING_TARGET` has already occurred on short legs.
  6. `OFFCOURSE` — Heading/bearing deviates by $> 10^\circ$ from the required bearing to target.
  7. `OK` — On track; no announcement needed.
* **Relative Bearing Correction:** `relativeBearingCorrection(location, ekfState)` returns angle offset in range $[-180.0, +180.0]$ (positive if heading is too far left and needs right correction, negative if facing too far right). `bearingCorrectionString` converts this to `"plus X degrees"` or `"minus X degrees"`.
* **One-Time Announcement Flags:** Messages 1 through 5 (`TARGET_REACHED`, `HIKE_ENDED`, `TOO_FAR`, `APPROACHING_TARGET`, `LEG_START`) are checked via boolean flags (`LegAnnouncementTracker`) before running geometric calculations to prevent duplicate announcements and save CPU cycles.
* **Dynamic Off-Course Cool-off Settings:** Configurable cool-off values for drift ($1\text{s} - 5\text{s}$, default $2\text{s}$) and steady off-course heading ($5\text{s} - 30\text{s}$, default $15\text{s}$) exposed in Map Settings.
* **TTS Message Generation (`stringify`):** Combines `LegState`, current `Leg`, `EkfState`, and relative bearing correction string into text messages emitted to `HikeRepository.hikeMessage` for Android Text-To-Speech playback.

### Telemetry Playback Engine & Interactive Controls
* **Deterministic Replay:** Ingests recorded CSV telemetry files and replays events sequentially through the EKF and `Hike` engine.
* **Playback Controls:** Provides `⏮` Step Back, `▶` Play / `⏸` Pause, `⏭` Step Forward, and `⏹` Stop controls with adjustable playback speed multiplier ($1\times - 10\times$).
* **Step Back Engine:** Synchronously resets EKF and re-executes trajectory up to the previous index for instant, accurate map state updates.

### Custom Map Canvas, Grid Overlays & Leg View Transitions
* **Intelligent Waypoint Name Placement:** Renders waypoint names on the map canvas using bisector angle placement relative to connected leg vectors, preventing text from crossing route leg lines.
* **Animated Leg Transitions:** Animates leg changes in Leg View over 2 seconds (20 steps). Interpolates start/target positions and applies scale rules (preserving scale for shorter legs or $<75\%$ viewport height; reducing scale so new leg height matches $75\%$ viewport height).
* **Interactive Compass View Toggle:** The compass needle badge in the upper-right corner doubles as an interactive button. Clicking it toggles between **Map View** (North-up) and **Leg View** (leg-focused with start at bottom and target at top).
* **Settings Window:** Accessed via a gear icon (`⚙`) in the lower-right corner of the map canvas. Allows configuring 1km labeled grid overlay, 100m unlabeled grid overlay, and off-course cooldown periods.

---

## 3. UI & Screen Specifications (Four Core Components)

1. **Header / Title Section:**
   * **Unloaded State:** Displays a primary option/button to load a CSV route file.
   * **Loaded State:** Displays Route Title, number of legs, and total route length in kilometers/meters, while retaining an option to load a new Route file at any time.

2. **Telemetry Dashboard:**
   * Displays current 6-State EKF values in real-time:
     * **Pos (x, y)** with position error/variance placed underneath.
     * **Bearing** with heading error/variance placed underneath.
     * **Speed** with velocity error/variance placed underneath.
     * **Step / Stride Length ($L$)** with stride variance placed underneath.
     * **Compass Bias ($b_\theta$)** with bias variance placed underneath.

3. **Maximized Abstract Map Canvas:**
   * Maximized Compose Canvas auto-scaled to encompass all route waypoints, active leg, and recorded breadcrumbs.
   * Upper-right controls include the interactive Compass Button (toggling Map View / Leg View) and relative bearing indicator.
   * Lower-right controls include "Fit All" view recentering and the Settings gear button (`⚙`) for grid overlays and off-course cooldowns.
