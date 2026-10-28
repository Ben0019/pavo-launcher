package com.ben.aurora;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.File;
import java.util.List;

/**
 * No-root remapping engine.  The physical controller's key events are consumed
 * by the accessibility service and replayed on a virtual controller created by
 * RootlessUinput.  Analogue axes remain on the physical device.
 */
final class RootlessRemap {
    private static RootlessRemap instance;
    private static final int MAX_CODE = 1024;

    private final Context context;
    private final RootlessUinput uinput = new RootlessUinput();
    private final ControllerMapConfig config = new ControllerMapConfig();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean[] pressed = new boolean[MAX_CODE];
    private final int[] held = new int[MAX_CODE];
    private final int[] sourceTarget = new int[MAX_CODE];
    private final long[] pendingAt = new long[MAX_CODE];
    private final boolean[] comboActive = new boolean[64];
    private final long[] comboReleaseAt = new long[64];
    private final Turbo[] turbo = new Turbo[MAX_CODE];
    private final File configFile;
    private long configStamp;
    private boolean tickPosted;
    private boolean active;

    private static final class Turbo {
        boolean active;
        int source;
        int target;
        boolean phase;
        long next;
    }

    private RootlessRemap(Context c) {
        context = c.getApplicationContext();
        configFile = ControllerRemapManager.configFile(context);
        for (int i = 0; i < turbo.length; i++) turbo[i] = new Turbo();
        loadConfig();
    }

    static synchronized boolean start(Context context) {
        if (instance != null && instance.isReady()) return true;
        stop();
        RootlessRemap engine = new RootlessRemap(context);
        if (!engine.uinput.start(context)) return false;
        engine.active = true;
        instance = engine;
        AuroraA11yService.restartControllerListener();
        return true;
    }

    static synchronized void stop() {
        RootlessRemap engine = instance;
        instance = null;
        if (engine != null) engine.shutdown();
    }

    static boolean isActive() {
        RootlessRemap engine = instance;
        return engine != null && engine.active && engine.uinput.isReady();
    }

    private boolean isReady() {
        return active && uinput.isReady();
    }

    private void shutdown() {
        active = false;
        handler.removeCallbacksAndMessages(null);
        releaseAll();
        uinput.stop();
    }

    /** Called from AccessibilityService.onKeyEvent. */
    static boolean handle(KeyEvent event) {
        RootlessRemap engine = instance;
        if (engine == null || !engine.active || event == null) return false;
        return engine.onKey(event);
    }

    static int mappedRaw(int source) {
        RootlessRemap engine = instance;
        if (engine == null || !engine.active) return source;
        return engine.config.targetFor(source);
    }

    static boolean isVirtualEvent(KeyEvent event) {
        RootlessRemap engine = instance;
        return engine != null && engine.isVirtualDevice(event);
    }

    private boolean onKey(KeyEvent event) {
        reloadIfChanged();
        if (!config.enabled || config.capture) return false;
        if (isVirtualDevice(event)) return false;
        int source = ControllerMapConfig.rawFor(event);
        if (source <= 0 || source >= MAX_CODE) return false;
        int sourceMask = event.getSource();
        boolean gamepad = (sourceMask & InputDevice.SOURCE_GAMEPAD) != 0
                || (sourceMask & InputDevice.SOURCE_JOYSTICK) != 0;
        if (!gamepad && !isOdinDevice(event)) return false;

        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return true;
            onDown(source);
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            onUp(source);
            return true;
        }
        return true;
    }

    private boolean isOdinDevice(KeyEvent event) {
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return false;
            InputDevice device = im.getInputDevice(event.getDeviceId());
            if (device == null) return false;
            String name = device.getName();
            return name != null && name.toLowerCase(java.util.Locale.US)
                    .contains("odin controller");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isVirtualDevice(KeyEvent event) {
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return false;
            InputDevice device = im.getInputDevice(event.getDeviceId());
            if (device == null) return false;
            String name = device.getName();
            return name != null && (name.contains(RootlessUinput.DEVICE_NAME)
                    || name.contains("Aurora Virtual Controller"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void onDown(int source) {
        if (pressed[source]) return;
        pressed[source] = true;

        for (int i = 0; i < config.combos.size() && i < comboActive.length; i++) {
            ControllerMapConfig.Combo combo = config.combos.get(i);
            if (comboActive[i] || !allPressed(combo)) continue;
            if (comboConflict(combo)) continue;
            comboActive[i] = true;
            comboReleaseAt[i] = SystemClock.uptimeMillis() + 55L;
            for (int key : combo.keys) pendingAt[key] = 0;
            outputKey(combo.target, true);
            scheduleTick();
            return;
        }

        if (comboMember(source)) {
            pendingAt[source] = SystemClock.uptimeMillis() + 70L;
            scheduleTick();
            return;
        }
        applyIndividual(source);
    }

    private void onUp(int source) {
        if (!pressed[source]) return;
        pressed[source] = false;

        Turbo t = turbo[source];
        if (t.active) {
            if (t.phase) outputKey(t.target, false);
            t.active = false;
            sourceTarget[source] = 0;
            return;
        }

        for (int i = 0; i < config.combos.size() && i < comboActive.length; i++) {
            if (!comboActive[i]) continue;
            ControllerMapConfig.Combo combo = config.combos.get(i);
            boolean participant = false;
            for (int key : combo.keys) if (key == source) participant = true;
            if (!participant) continue;
            if (!anyPressed(combo)) comboActive[i] = false;
            return;
        }

        if (pendingAt[source] > 0) {
            pendingAt[source] = 0;
            releaseIndividual(source, true);
            return;
        }
        releaseIndividual(source, false);
    }

    private void tick() {
        tickPosted = false;
        long now = SystemClock.uptimeMillis();
        boolean more = false;
        for (int i = 0; i < MAX_CODE; i++) {
            if (pendingAt[i] > 0) {
                if (now >= pendingAt[i]) {
                    pendingAt[i] = 0;
                    if (pressed[i]) applyIndividual(i);
                } else {
                    more = true;
                }
            }
            Turbo t = turbo[i];
            if (t.active && pressed[i]) {
                if (now >= t.next) {
                    outputKey(t.target, !t.phase);
                    t.phase = !t.phase;
                    t.next = now + 65L;
                }
                more = true;
            }
        }
        for (int i = 0; i < config.combos.size() && i < comboActive.length; i++) {
            if (comboActive[i] && now >= comboReleaseAt[i]) {
                outputKey(config.combos.get(i).target, false);
                comboActive[i] = false;
            }
            if (comboActive[i]) more = true;
        }
        if (more) scheduleTick();
    }

    private void scheduleTick() {
        if (tickPosted) return;
        tickPosted = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 8L);
    }

    private void applyIndividual(int source) {
        int target = config.targetFor(source);
        if (config.turbo.contains(source)) {
            Turbo t = turbo[source];
            t.active = true;
            t.source = source;
            t.target = target;
            t.phase = true;
            t.next = SystemClock.uptimeMillis() + 65L;
            outputKey(target, true);
            sourceTarget[source] = target;
            scheduleTick();
        } else {
            outputKey(target, true);
            sourceTarget[source] = target;
        }
    }

    private void releaseIndividual(int source, boolean quickTap) {
        Turbo t = turbo[source];
        if (t.active) {
            if (t.phase) outputKey(t.target, false);
            t.active = false;
            sourceTarget[source] = 0;
            return;
        }
        int target = sourceTarget[source];
        if (target == 0) target = config.targetFor(source);
        if (quickTap) {
            outputKey(target, true);
            outputKey(target, false);
        } else {
            outputKey(target, false);
        }
        sourceTarget[source] = 0;
    }

    private void outputKey(int raw, boolean down) {
        if (raw <= 0 || raw >= MAX_CODE) return;
        if (down) {
            if (held[raw]++ == 0) uinput.inject(raw, true);
        } else if (held[raw] > 0) {
            if (--held[raw] == 0) uinput.inject(raw, false);
        }
    }

    private boolean allPressed(ControllerMapConfig.Combo combo) {
        for (int key : combo.keys) if (!pressed[key]) return false;
        return true;
    }

    private boolean anyPressed(ControllerMapConfig.Combo combo) {
        for (int key : combo.keys) if (pressed[key]) return true;
        return false;
    }

    private boolean comboMember(int source) {
        for (ControllerMapConfig.Combo combo : config.combos) {
            for (int key : combo.keys) if (key == source) return true;
        }
        return false;
    }

    private boolean comboConflict(ControllerMapConfig.Combo combo) {
        for (int key : combo.keys) {
            if (sourceTarget[key] != 0 || turbo[key].active) return true;
        }
        return false;
    }

    private void releaseAll() {
        for (int i = 0; i < MAX_CODE; i++) {
            if (held[i] > 0) {
                held[i] = 0;
                uinput.inject(i, false);
            }
            pressed[i] = false;
            sourceTarget[i] = 0;
            pendingAt[i] = 0;
            turbo[i].active = false;
        }
        for (int i = 0; i < comboActive.length; i++) comboActive[i] = false;
    }

    private void loadConfig() {
        config.load(configFile);
        configStamp = configFile.lastModified() * 31L + configFile.length();
    }

    private void reloadIfChanged() {
        long stamp = configFile.lastModified() * 31L + configFile.length();
        if (stamp == configStamp) return;
        configStamp = stamp;
        releaseAll();
        config.load(configFile);
    }
}
