package com.example.gyrocursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
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
    static final int DEFAULT_SENSITIVITY = 50;

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
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean enabled = true;
    private float sensitivity = DEFAULT_SENSITIVITY;
    private boolean invertX, invertY;
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
        density =getResources().getDisplayMetrics().density;

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

        updateState();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        sensorsRegistered = false;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        removeOverlay();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    // ---------------------------------------------------------------- settings

    private void loadPrefs() {
        enabled = prefs.getBoolean(KEY_ENABLED, true);
        sensitivity = Math.max(1, prefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY));
        invertX = prefs.getBoolean(KEY_INVERT_X, false);
        invertY = prefs.getBoolean(KEY_INVERT_Y, false);
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

    private void addOverlay() {
        cursorView = new CursorView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
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
        windowManager.addView(cursorView, lp);
    }

    private void removeOverlay() {
        if (cursorView == null) return;
        try { windowManager.removeView(cursorView); } catch (Exception ignored) { }
        cursorView = null;
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
        CursorView view = cursorView;
        if (dt <= 0f || dt > 0.1f || view == null || view.getWidth() == 0) return;

        // Device axes -> current screen axes (x right, y up, z towards you),
        // so it still works in landscape.
        int rotation = displayRotation(view);
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
        view.moveBy(vx * dt * pxPerRadian, vy * dt * pxPerRadian);

        // Finger held down? Drag it along with the dot.
        if (fingerIsDown && !segmentInFlight) sendNextSegment();
    }

    private int displayRotation(View view) {
        Display d = view.getDisplay();
        if (d == null) d = windowManager.getDefaultDisplay();
        return d == null ? Surface.ROTATION_0 : d.getRotation();
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
        CursorView view = cursorView;
        if (view == null || view.getHeight() == 0) return;
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        float x = origin[0] + view.getWidth() / 2f;
        Path path = new Path();
        path.moveTo(x, origin[1] + view.getHeight() * 0.80f);
        path.lineTo(x, origin[1] + view.getHeight() * 0.30f);
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
        return new float[]{Math.max(0f, origin[0] + view.cx), Math.max(0f, origin[1] + view.cy)};
    }

    // ---------------------------------------------------------------- the dot

    /** Full-screen, see-through view that only draws the dot. */
    static final class CursorView extends View {
        float cx = -1f, cy = -1f;
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

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (cx < 0f || oldW == 0 || oldH == 0) {   // start in the middle
                cx = w / 2f;
                cy = h / 2f;
            } else {                                   // keep relative spot on rotation
                cx = cx * w / oldW;
                cy = cy * h / oldH;
            }
            clamp();
        }

        void moveBy(float dx, float dy) {
            if (dx == 0f && dy == 0f) return;
            cx += dx;
            cy += dy;
            clamp();
            invalidate();
        }

        private void clamp() {
            cx = Math.max(0f, Math.min(getWidth() - 1f, cx));
            cy = Math.max(0f, Math.min(getHeight() - 1f, cy));
        }

        /** Little expanding ring so you can see the tap happened. */
        void flash() {
            flashStart = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
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
