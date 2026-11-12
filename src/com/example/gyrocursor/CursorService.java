package com.example.gyrocursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.display.DisplayManager;
import android.os.HandlerThread;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Draws a dot over every app and moves it with the gyroscope.
 * Volume Down twice = a finger on the dot (tap / hold / drag), Volume Up twice = Back.
 *
 * Being an AccessibilityService is what allows it to (a) draw over everything,
 * (b) see the volume key in any app and (c) inject taps. Android re-starts it
 * by itself after a reboot, so there is no boot receiver.
 */
public class CursorService extends AccessibilityService
        implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {

    static final String PREFS = "gyro_cursor";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_SENSITIVITY = "sensitivity";
    static final String KEY_INVERT_X = "invert_x";
    static final String KEY_INVERT_Y = "invert_y";
    static final String KEY_AIM_ASSIST = "aim_assist";
    static final int DEFAULT_SENSITIVITY = 50;

    /** True while the service is actually running (the settings screen shows it). */
    static volatile boolean running;

    /** Max time between the two Volume Down presses to count as a double press. */
    private static final long DOUBLE_PRESS_MS = 350;
    /** Length of each piece of the virtual finger's movement. */
    private static final long SEGMENT_MS = 20;
    /** Gyro "dead zone" in rad/s: ignores hand tremor, ramps up to full speed. */
    private static final float DEADZONE_LO = 0.01f, DEADZONE_HI = 0.05f;

    private WindowManager windowManager;
    private SensorManager sensorManager;
    private AudioManager audioManager;
    private KeyguardManager keyguardManager;
    private Sensor gyroSensor, gravitySensor;
    private boolean gravityFromAccelerometer;
    private SharedPreferences prefs;
    private CursorView cursorView;
    private Display display;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean enabled = true;
    private float sensitivity = DEFAULT_SENSITIVITY;
    private boolean invertX, invertY;
    private boolean aimAssist = true;
    private float density = 1f;

    private boolean screenOn = true;
    private boolean sensorsRegistered;
    private long lastGyroTimestamp;
    private final float[] gravity = {0f, 9.81f, 0f};
    private final float[] w = new float[3];   // angular velocity, screen axes
    private final float[] g = new float[3];   // gravity, screen axes

    private boolean singlePressPending;

    /** First press with no second one in time: behave like a normal volume-down. */
    private final Runnable singlePress = new Runnable() {
        @Override public void run() {
            singlePressPending = false;
            lowerVolume();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            updateState();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        density = getResources().getDisplayMetrics().density;
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        display = dm.getDisplay(Display.DEFAULT_DISPLAY);

        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravitySensor == null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gravityFromAccelerometer = true;
        }

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        loadPrefs();

        // Stop the sensors while the screen is off to save battery.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = pm == null || pm.isInteractive();

        startScanner();
        updateState();
        running = true;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (scanThread != null) scanThread.quitSafely();
        handler.removeCallbacksAndMessages(null);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        sensorsRegistered = false;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        removeOverlay();
        super.onDestroy();
    }

    /** Something on screen changed (new screen, scrolled, updated): rescan the buttons. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (aimAssist && enabled && screenOn) requestScan();
    }
    @Override public void onInterrupt() { }

    // ---------------------------------------------------------------- settings

    private void loadPrefs() {
        enabled = prefs.getBoolean(KEY_ENABLED, true);
        sensitivity = Math.max(1, prefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY));
        invertX = prefs.getBoolean(KEY_INVERT_X, false);
        invertY = prefs.getBoolean(KEY_INVERT_Y, false);
        aimAssist = prefs.getBoolean(KEY_AIM_ASSIST, true);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        loadPrefs();
        updateState();
    }

    /** Shows/hides the dot and starts/stops the sensors to match the current state. */
    private void updateState() {
        if (fingerIsDown && (!enabled || !screenOn)) {   // lift the finger if we're going away
            releaseRequested = true;
            if (!segmentInFlight) sendNextSegment();
        }
        if (enabled && cursorView == null) addOverlay();
        if (!enabled && cursorView != null) removeOverlay();

        if (aimAssist && enabled && screenOn) requestScan();
        if (!aimAssist) setHighlight(null);

        boolean wantSensors = enabled && screenOn && cursorView != null;
        if (wantSensors && !sensorsRegistered) {
            lastGyroTimestamp = 0;
            if (gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
            }
            if (gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME);
            }
            sensorsRegistered = true;
        } else if (!wantSensors && sensorsRegistered) {
            sensorManager.unregisterListener(this);
            sensorsRegistered = false;
        }
    }

    // ---------------------------------------------------------------- overlay
    //
    // The overlay window is only as big as the dot and is moved around the screen.
    // (A full-screen see-through window costs ~50 MB of graphics memory, which made
    // Xiaomi kill the app first whenever the camera needed memory.)

    private WindowManager.LayoutParams overlayParams;
    private int windowSize;                       // px, square window
    private int screenW, screenH;                 // px, in the current rotation
    private float cursorX = -1f, cursorY = -1f;   // centre of the dot, screen px

    private void addOverlay() {
        windowSize = Math.round(64 * density);
        updateScreenSize();
        if (cursorX < 0f) {                       // start in the middle
            cursorX = screenW / 2f;
            cursorY = screenH / 2f;
        }
        addOutline();                             // added first so the dot stays on top
        cursorView = new CursorView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                windowSize,
                windowSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Touches go straight through to the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0); // cover status + navigation bar too
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        overlayParams = lp;
        placeWindow();
        windowManager.addView(cursorView, lp);
    }

    private void removeOverlay() {
        removeOutline();
        if (cursorView == null) return;
        try { windowManager.removeView(cursorView); } catch (Exception ignored) { }
        cursorView = null;
    }

    /** Moves the dot by (dx, dy) pixels. */
    private void moveCursor(float dx, float dy) {
        if (dx == 0f && dy == 0f) return;
        cursorX += dx;
        cursorY += dy;
        relayout();
    }

    /** Keeps the dot on screen and moves the window to it if needed. */
    private void relayout() {
        cursorX = Math.max(0f, Math.min(screenW - 1f, cursorX));
        cursorY = Math.max(0f, Math.min(screenH - 1f, cursorY));
        if (placeWindow() && cursorView != null) {
            try { windowManager.updateViewLayout(cursorView, overlayParams); } catch (Exception ignored) { }
        }
    }

    /** Centres the window on the dot. Returns true if the window position changed. */
    private boolean placeWindow() {
        int x = Math.round(cursorX - windowSize / 2f);
        int y = Math.round(cursorY - windowSize / 2f);
        if (x == overlayParams.x && y == overlayParams.y) return false;
        overlayParams.x = x;
        overlayParams.y = y;
        return true;
    }

    @SuppressWarnings("deprecation")
    private void updateScreenSize() {
        Point size = new Point();
        if (display != null) display.getRealSize(size);
        screenW = size.x;
        screenH = size.y;
    }

    /** Screen rotated: keep the dot at the same relative spot. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (cursorView == null) return;
        int oldW = screenW, oldH = screenH;
        updateScreenSize();
        if (oldW > 0 && oldH > 0 && (oldW != screenW || oldH != screenH)) {
            cursorX = cursorX * screenW / oldW;
            cursorY = cursorY * screenH / oldH;
        }
        relayout();
    }

    // ---------------------------------------------------------------- sensors

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            onGyro(e);
        } else if (gravityFromAccelerometer) {
            for (int i = 0; i < 3; i++) gravity[i] += 0.1f * (e.values[i] - gravity[i]); // low-pass
        } else {
            System.arraycopy(e.values, 0, gravity, 0, 3);
        }
    }

    private void onGyro(SensorEvent e) {
        long last = lastGyroTimestamp;
        lastGyroTimestamp = e.timestamp;
        if (last == 0) return;
        float dt = (e.timestamp - last) * 1e-9f;
        if (dt <= 0f || dt > 0.1f || cursorView == null || screenW == 0) return;

        // Device axes -> current screen axes (x right, y up, z towards you),
        // so it still works in landscape.
        int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        toScreen(e.values, rotation, w);
        toScreen(gravity, rotation, g);

        // Horizontal: turning around the real "up" axis (from gravity), so it feels
        // the same whether you hold the phone upright, tilted, or flat.
        // ("player space" gyro: yaw + roll weighted by gravity, relaxed by 1.41)
        float yaw;
        float gLen = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (gLen > 1f) {
            float worldYaw = (w[1] * g[1] + w[2] * g[2]) / gLen;
            float localYaw = (float) Math.sqrt(w[1] * w[1] + w[2] * w[2]);
            yaw = Math.signum(worldYaw) * Math.min(Math.abs(worldYaw) * 1.41f, localYaw);
        } else {
            yaw = w[1];
        }
        // Vertical: tilting around the screen's horizontal axis.
        float pitch = w[0];

        // Like a laser pointer out of the back of the phone:
        // turn right -> dot goes right, tilt top towards you -> dot goes up.
        float vx = -yaw;
        float vy = -pitch;

        float speed = (float) Math.sqrt(vx * vx + vy * vy);
        if (speed < DEADZONE_HI) {
            float k = speed <= DEADZONE_LO ? 0f : (speed - DEADZONE_LO) / (DEADZONE_HI - DEADZONE_LO);
            vx *= k;
            vy *= k;
        }
        if (invertX) vx = -vx;
        if (invertY) vy = -vy;

        float pxPerRadian = sensitivity * 12f * density;
        float dx = vx * dt * pxPerRadian;
        float dy = vy * dt * pxPerRadian;
        if (aimAssist && !fingerIsDown) {           // no snapping while dragging/scrolling
            float[] m = aimAssist(dx, dy, (float) Math.sqrt(vx * vx + vy * vy), dt);
            dx = m[0];
            dy = m[1];
        }
        moveCursor(dx, dy);
        if (aimAssist) setHighlight(fingerIsDown ? null : targetUnderDot());

        // Finger held down? Drag it along with the dot.
        if (fingerIsDown && !segmentInFlight) sendNextSegment();
    }

    private static void toScreen(float[] v, int rotation, float[] out) {
        float x = v[0], y = v[1];
        switch (rotation) {
            case Surface.ROTATION_90:  out[0] = -y; out[1] = x;  break;
            case Surface.ROTATION_180: out[0] = -x; out[1] = -y; break;
            case Surface.ROTATION_270: out[0] = y;  out[1] = -x; break;
            default:                   out[0] = x;  out[1] = y;  break;
        }
        out[2] = v[2];
    }

    // ---------------------------------------------------------------- volume key
    //
    // Volume Down twice = a virtual finger:
    //   2nd press goes down  -> finger touches the screen at the dot
    //   keep holding + move   -> finger drags with the dot (scroll, swipe, drag)
    //   release               -> finger lifts
    // So a quick double press is a tap, and double press + hold is a long press.

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if ((code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP)
                || !enabled || !screenOn || cursorView == null) {
            return false; // let Android handle it normally
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP) return onVolumeUp(event);

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                if (singlePressPending) {          // second press in time -> finger down
                    handler.removeCallbacks(singlePress);
                    singlePressPending = false;
                    fingerDown();
                } else {                           // first press -> wait for a second one
                    singlePressPending = true;
                    handler.postDelayed(singlePress, DOUBLE_PRESS_MS);
                }
            } else if (!fingerIsDown) {            // single press held -> normal volume down
                handler.removeCallbacks(singlePress);
                singlePressPending = false;
                lowerVolume();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && fingerIsDown) {
            fingerUp();
        }
        return true; // we handled it (no double volume change)
    }

    /** Volume Up twice = Back. One press (or holding) still raises the volume. */
    private boolean onVolumeUp(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                if (singleUpPending) {             // second press in time -> UNLOCK or BACK
                    handler.removeCallbacks(singleUpPress);
                    singleUpPending = false;
                    if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                        swipeUpToUnlock();
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                } else {                           // first press -> wait for a second one
                    singleUpPending = true;
                    handler.postDelayed(singleUpPress, DOUBLE_PRESS_MS);
                }
            } else {                               // held -> normal volume up
                handler.removeCallbacks(singleUpPress);
                singleUpPending = false;
                raiseVolume();
            }
        }
        return true;
    }

    /**
     * Lock screen showing: swipe up like a finger would. With no screen lock this
     * unlocks; with a PIN/pattern/fingerprint Android shows its unlock prompt.
     */
    private void swipeUpToUnlock() {
        if (screenW == 0 || screenH == 0) return;
        float x = screenW / 2f;
        Path path = new Path();
        path.moveTo(x, screenH * 0.80f);
        path.lineTo(x, screenH * 0.30f);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 250))
                .build(), null, null);
    }

    private boolean singleUpPending;

    private final Runnable singleUpPress = new Runnable() {
        @Override public void run() {
            singleUpPending = false;
            raiseVolume();
        }
    };

    private void lowerVolume() {
        audioManager.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private void raiseVolume() {
        audioManager.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    // ---------------------------------------------------------------- virtual finger
    //
    // Built from "continued" gesture strokes: each short segment leaves the finger
    // down (willContinue = true) and the next one starts exactly where it ended.
    // Only one segment is in flight at a time; the next is sent when it completes.

    private boolean fingerIsDown;        // 2nd press is being held
    private boolean releaseRequested;    // key released, lift after current segment
    private boolean segmentInFlight;
    private GestureDescription.StrokeDescription lastStroke;
    private float lastX, lastY;          // where the finger is now (screen px)

    private final GestureResultCallback segmentCallback = new GestureResultCallback() {
        @Override public void onCompleted(GestureDescription gesture) {
            segmentInFlight = false;
            if (fingerIsDown) sendNextSegment();
        }
        @Override public void onCancelled(GestureDescription gesture) {
            segmentInFlight = false;
            resetFinger();
        }
    };

    private void fingerDown() {
        CursorView view = cursorView;
        if (view == null) return;
        float[] p = cursorOnScreen(view);
        Path path = new Path();
        path.moveTo(p[0], p[1]);
        lastX = p[0];
        lastY = p[1];
        fingerIsDown = true;
        releaseRequested = false;
        lastStroke = new GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true);
        dispatchSegment(lastStroke);
        view.setHolding(true);
        view.flash();
    }

    private void fingerUp() {
        releaseRequested = true;
        if (!segmentInFlight) sendNextSegment();
    }

    /** Moves the finger to the dot (if it moved) and/or lifts it (if released). */
    private void sendNextSegment() {
        CursorView view = cursorView;
        if (!fingerIsDown || lastStroke == null) { resetFinger(); return; }
        float[] p = view != null ? cursorOnScreen(view) : new float[]{lastX, lastY};
        boolean moved = Math.abs(p[0] - lastX) >= 1f || Math.abs(p[1] - lastY) >= 1f;
        boolean lift = releaseRequested || view == null;  // dot gone -> always lift
        if (!moved && !lift) return;              // finger just stays where it is

        Path path = new Path();
        path.moveTo(lastX, lastY);
        if (moved) path.lineTo(p[0], p[1]);
        lastX = p[0];
        lastY = p[1];
        lastStroke = lastStroke.continueStroke(path, 0, SEGMENT_MS, !lift);
        dispatchSegment(lastStroke);
        if (lift) {
            fingerIsDown = false;
            if (view != null) view.setHolding(false);
        }
    }

    private void dispatchSegment(GestureDescription.StrokeDescription stroke) {
        segmentInFlight = true;
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        if (!dispatchGesture(g, segmentCallback, null)) {
            segmentInFlight = false;
            resetFinger();
        }
    }

    private void resetFinger() {
        fingerIsDown = false;
        releaseRequested = false;
        lastStroke = null;
        if (cursorView != null) cursorView.setHolding(false);
    }

    private static float[] cursorOnScreen(CursorView view) {
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        // The dot is drawn in the middle of its small window.
        return new float[]{
                Math.max(0f, origin[0] + view.getWidth() / 2f),
                Math.max(0f, origin[1] + view.getHeight() / 2f)};
    }

    // ---------------------------------------------------------------- aim assist
    //
    // Like aim assist in shooters. A background thread asks Android where the
    // clickable things are (buttons, links, toggles, text fields - positions only).
    // Then, every gyro update:
    //   - over a button the dot moves slower (easy to stay on it),
    //   - near a button and moving slowly, the dot is pulled onto it,
    //   - the button under the dot gets an outline.
    // Moving the phone faster breaks free.

    /** Speed multiplier while the dot is over a button. */
    private static final float FRICTION = 0.45f;
    /** How fast the pull works (1/s). */
    private static final float PULL_RATE = 10f;
    /** Turning faster than this (rad/s) switches the pull off. */
    private static final float PULL_FADE_SPEED = 0.35f;
    /** Don't rescan more often than this. */
    private static final long SCAN_INTERVAL_MS = 250;

    /** Clickable rectangles of one window, in screen pixels. */
    private static final class WindowTargets {
        final Rect bounds = new Rect();
        final ArrayList<Rect> rects = new ArrayList<>();
        int layer;
    }

    private HandlerThread scanThread;
    private Handler scanHandler;
    /** Latest scan, top window first. Replaced as a whole, never modified. */
    private volatile WindowTargets[] targets = new WindowTargets[0];
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private volatile long lastScanTime;

    private final Runnable scanRunnable = new Runnable() {
        @Override public void run() {
            scanQueued.set(false);
            lastScanTime = SystemClock.uptimeMillis();
            targets = scanTargets();
            handler.post(refreshHighlight);
        }
    };

    /** Screen changed while the dot stands still: update the outline too. */
    private final Runnable refreshHighlight = new Runnable() {
        @Override public void run() {
            if (aimAssist && cursorView != null) setHighlight(targetUnderDot());
        }
    };

    private void startScanner() {
        scanThread = new HandlerThread("gyro-cursor-scan");
        scanThread.start();
        scanHandler = new Handler(scanThread.getLooper());
    }

    /** Asks for a rescan soon (at most every SCAN_INTERVAL_MS). */
    private void requestScan() {
        if (scanHandler == null || !scanQueued.compareAndSet(false, true)) return;
        long wait = Math.max(0, lastScanTime + SCAN_INTERVAL_MS - SystemClock.uptimeMillis());
        scanHandler.postDelayed(scanRunnable, wait);
    }

    /** Runs on the scan thread. */
    private WindowTargets[] scanTargets() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Exception e) {
            return new WindowTargets[0];
        }
        long maxArea = (long) screenW * screenH / 2;    // ignore full-screen "buttons"
        ArrayList<WindowTargets> out = new ArrayList<>();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue; // our dot
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            WindowTargets wt = new WindowTargets();
            w.getBoundsInScreen(wt.bounds);
            wt.layer = w.getLayer();
            collectClickables(root, wt.rects, maxArea, 0);
            out.add(wt);
        }
        Collections.sort(out, new Comparator<WindowTargets>() {     // top window first
            @Override public int compare(WindowTargets a, WindowTargets b) {
                return Integer.compare(b.layer, a.layer);
            }
        });
        return out.toArray(new WindowTargets[0]);
    }

    private static void collectClickables(AccessibilityNodeInfo node, ArrayList<Rect> out,
                                          long maxArea, int depth) {
        if (node == null || depth > 60 || out.size() > 400) return;
        if (!node.isVisibleToUser()) return;        // hidden -> its children are hidden too
        if (node.isClickable() || node.isLongClickable() || node.isCheckable() || node.isEditable()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (!r.isEmpty() && (long) r.width() * r.height() <= maxArea) out.add(r);
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) collectClickables(node.getChild(i), out, maxArea, depth + 1);
    }

    /**
     * The button the dot is on or near: only looks at the top window under the dot,
     * prefers the closest one, and for nested ones (button inside a row) the smallest.
     */
    private Rect findTarget(float x, float y, float margin) {
        for (WindowTargets wt : targets) {
            if (!wt.bounds.contains((int) x, (int) y)) continue;
            Rect best = null;
            float bestDist = Float.MAX_VALUE;
            long bestArea = Long.MAX_VALUE;
            for (Rect r : wt.rects) {
                float dx = Math.max(Math.max(r.left - x, 0f), x - r.right);
                float dy = Math.max(Math.max(r.top - y, 0f), y - r.bottom);
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > margin) continue;
                long area = (long) r.width() * r.height();
                if (dist < bestDist - 0.5f || (Math.abs(dist - bestDist) <= 0.5f && area < bestArea)) {
                    best = r;
                    bestDist = dist;
                    bestArea = area;
                }
            }
            return best;    // only the top window under the dot counts
        }
        return null;
    }

    private Rect targetUnderDot() {
        return findTarget(cursorX, cursorY, 0f);
    }

    /**
     * Applies slowdown + pull to a planned move (dx, dy) and returns the new move.
     * speed = how fast the phone is turning (rad/s).
     */
    private float[] aimAssist(float dx, float dy, float speed, float dt) {
        Rect t = findTarget(cursorX, cursorY, 28f * density);
        if (t != null) {
            if (t.contains((int) cursorX, (int) cursorY)) {     // on it: slow down
                dx *= FRICTION;
                dy *= FRICTION;
            }
            float pull = Math.max(0f, 1f - speed / PULL_FADE_SPEED);
            if (pull > 0f) {                                     // near it, moving slowly: pull
                float k = pull * (1f - (float) Math.exp(-PULL_RATE * dt));
                dx += (aimPoint(t.left, t.right, cursorX) - cursorX) * k;
                dy += (aimPoint(t.top, t.bottom, cursorY) - cursorY) * k;
            }
        }
        return new float[]{dx, dy};
    }

    /** Small button: its centre. Big one (like a list row): nearest spot a bit inside. */
    private float aimPoint(int lo, int hi, float c) {
        float inset = 24f * density;
        if (hi - lo <= 120f * density) return (lo + hi) / 2f;
        return Math.max(lo + inset, Math.min(hi - inset, c));
    }

    // ---------------------------------------------------------------- outline

    private OutlineView outlineView;
    private WindowManager.LayoutParams outlineParams;
    private final Rect outlined = new Rect();

    private void addOutline() {
        outlineView = new OutlineView(this);
        outlineView.setVisibility(View.INVISIBLE);
        outlineParams = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        outlineParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) outlineParams.setFitInsetsTypes(0);
        windowManager.addView(outlineView, outlineParams);
    }

    private void removeOutline() {
        if (outlineView == null) return;
        try { windowManager.removeView(outlineView); } catch (Exception ignored) { }
        outlineView = null;
        outlined.setEmpty();
    }

    /** Outlines r (or hides the outline when r is null). */
    private void setHighlight(Rect r) {
        if (outlineView == null) return;
        if (r == null) {
            if (!outlined.isEmpty()) {
                outlined.setEmpty();
                outlineView.setVisibility(View.INVISIBLE);
            }
            return;
        }
        if (r.equals(outlined)) return;
        outlined.set(r);
        int pad = Math.round(3 * density);
        outlineParams.x = r.left - pad;
        outlineParams.y = r.top - pad;
        outlineParams.width = r.width() + 2 * pad;
        outlineParams.height = r.height() + 2 * pad;
        try { windowManager.updateViewLayout(outlineView, outlineParams); } catch (Exception ignored) { }
        outlineView.setVisibility(View.VISIBLE);
    }

    /** Rounded red frame drawn around the button under the dot. */
    static final class OutlineView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float corner;

        OutlineView(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            corner = 8f * d;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.5f * d);
            stroke.setColor(0xE6FF3B30);
            fill.setColor(0x1FFF3B30);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = stroke.getStrokeWidth() / 2f;
            box.set(half, half, getWidth() - half, getHeight() - half);
            canvas.drawRoundRect(box, corner, corner, fill);
            canvas.drawRoundRect(box, corner, corner, stroke);
        }
    }

    // ---------------------------------------------------------------- the dot

    /** Small see-through view that draws the dot in its centre. */
    static final class CursorView extends View {
        private final float radius;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pulse = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long flashStart;
        private boolean holding;

        CursorView(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            radius = 8f * d;
            fill.setColor(0xF0FF3B30);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2.5f * d);
            ring.setColor(0xFFFFFFFF);
            shadow.setStyle(Paint.Style.STROKE);
            shadow.setStrokeWidth(1.5f * d);
            shadow.setColor(0x80000000);
            pulse.setStyle(Paint.Style.STROKE);
            pulse.setStrokeWidth(3f * d);
            pulse.setColor(0xFFFF3B30);
            halo.setColor(0x55FF3B30);
        }

        /** Finger is down: bigger dot with a halo so you know you're dragging. */
        void setHolding(boolean h) {
            if (holding == h) return;
            holding = h;
            invalidate();
        }

        /** Little expanding ring so you can see the tap happened. */
        void flash() {
            flashStart = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (flashStart != 0) {
                float t = (SystemClock.uptimeMillis() - flashStart) / 250f;
                if (t < 1f) {
                    pulse.setAlpha((int) (255 * (1f - t)));
                    canvas.drawCircle(cx, cy, radius * (1f + 2.5f * t), pulse);
                    postInvalidateOnAnimation();
                } else {
                    flashStart = 0;
                }
            }
            float r = holding ? radius * 1.35f : radius;
            if (holding) canvas.drawCircle(cx, cy, r * 2.2f, halo);
            canvas.drawCircle(cx, cy, r + ring.getStrokeWidth(), shadow);
            canvas.drawCircle(cx, cy, r, fill);
            canvas.drawCircle(cx, cy, r, ring);
        }
    }
}
