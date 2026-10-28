#define _GNU_SOURCE
/*
 * Aurora Touch Mapper helper.
 *
 * Runs as root through PServerBinder, grabs the Odin controller and creates a
 * virtual multitouch screen. Controller buttons are mapped to normalized touch
 * positions from a simple config file.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAX_TOUCH 10

#define RIGHT_OFF 0
#define RIGHT_SWIPE 1
#define RIGHT_MOUSE 2
#define RIGHT_MOBA 3
#define RIGHT_JOYSTICK 4
#define RIGHT_AIM 5
#define MAX_COMBOS 48
#define MAX_SHORTCUTS 8
#define SHORTCUT_SOCKET_NAME "aurora-touchmap-shortcuts"

struct map_entry {
    int code;
    float x;
    float y;
    int slot;
    int down;
};

static struct map_entry maps[KEY_MAX + 1];
static int map_count;
static int ui_fd = -1;
static int src_fd = -1;
static volatile int stop_requested;
static int next_tracking = 1;
static int hat_x;
static int hat_y;
static char pid_path[512];
static int client_pid;

static int stick_enabled;
static int stick_axis_x = ABS_X;
static int stick_axis_y = ABS_Y;
static float stick_cx = 0.18f;
static float stick_cy = 0.62f;
static float stick_radius = 0.16f;
static int stick_raw_x;
static int stick_raw_y;
static int stick_slot = -1;
static int stick_down;

static int right_mode;
static int right_axis_x = ABS_Z;
static int right_axis_y = ABS_RZ;
static float right_cx = 0.78f;
static float right_cy = 0.64f;
static float right_radius = 0.16f;
static int right_raw_x;
static int right_raw_y;
static int right_slot = -1;
static int right_down;
static int right_hold_enabled;
static int right_hold_code = BTN_THUMBR;
static int right_held = 1;

#define COMBO_LEFT_JOY 3001
#define COMBO_RIGHT_JOY 3002
#define COMBO_CODE_MAX 3002
#define COMBO_STICK_RADIUS 0.16f

struct combo_entry {
    int modifier;
    int button;
    float x;
    float y;
    float radius;
    int active;
    int down;
    int slot;
    int gesture_armed;
    int gesture_moved;
    int gesture_tapped;
};

static struct combo_entry combos[MAX_COMBOS];
static int combo_count;
static int combo_mod_down[COMBO_CODE_MAX + 1];
static int shortcut_codes[MAX_SHORTCUTS];
static int shortcut_count;
static int shortcut_fd = -1;
static int combo_left_joy_down;
static int combo_right_joy_down;
static int combo_stick_index = -1;

static int r3_stick_enabled;
static float r3_cx = 0.80f;
static float r3_cy = 0.60f;
static float r3_radius = 0.16f;
static int r3_held;
static int r3_used;
static float aim_x;
static float aim_input;
static float aim_speed;
static long long aim_last_ms;

static int mouse_fd = -1;
static float mouse_accum_x;
static float mouse_accum_y;
static int mouse_left_down;
static int mouse_right_down;
static long long last_mouse_ms;
static long long monotonic_ms(void);
static int aim_debug;

static void on_signal(int sig) {
    (void)sig;
    stop_requested = 1;
}

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    if (ui_fd >= 0) {
        ssize_t ignored = write(ui_fd, &ev, sizeof(ev));
        (void)ignored;
    }
}

static void sync_report(void) {
    emit(EV_SYN, SYN_REPORT, 0);
}

static int map_for(int code) {
    if (code < 0 || code > KEY_MAX) return -1;
    if (!maps[code].code) return -1;
    return code;
}

static int right_touch_enabled(void) {
    return right_mode == RIGHT_SWIPE || right_mode == RIGHT_MOBA
            || right_mode == RIGHT_JOYSTICK || right_mode == RIGHT_AIM;
}

static int right_gate_open(void) {
    return !right_hold_enabled || right_held;
}

static int r3_zone_active(void) {
    return r3_stick_enabled && r3_held;
}

static int combo_stick_active(void) {
    return combo_stick_index >= 0 && combo_stick_index < combo_count
            && combos[combo_stick_index].active;
}

static float active_right_cx(void) {
    if (r3_zone_active()) return r3_cx;
    if (combo_stick_active()) return combos[combo_stick_index].x;
    return right_cx;
}

static float active_right_cy(void) {
    if (r3_zone_active()) return r3_cy;
    if (combo_stick_active()) return combos[combo_stick_index].y;
    return right_cy;
}

static float active_right_radius(void) {
    if (r3_zone_active()) return r3_radius;
    if (combo_stick_active()) return combos[combo_stick_index].radius;
    return right_radius;
}

static void touch_down(int code);
static void touch_up(int code);
static int free_slot(void);
static int any_touch(void);
static void touch_coords(float sx, float sy, int *raw_x, int *raw_y);
static void right_release(void);
static void right_start_at(float sx, float sy);
static void right_emit_position(void);
static void combo_stick_activate(int index);
static void combo_stick_deactivate(int index);
static void combo_stick_claim(int index);
static void combo_stick_release_handover(int index);
static void combo_tap(int index);
static void aim_tick(void);

static int shortcut_is_reserved(int code) {
    for (int i = 0; i < shortcut_count; i++) {
        if (shortcut_codes[i] == code) return 1;
    }
    return 0;
}

static int shortcut_connect(void) {
    if (shortcut_fd >= 0) return 1;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return 0;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = 0;
    size_t len = strlen(SHORTCUT_SOCKET_NAME);
    if (len > sizeof(addr.sun_path) - 2) len = sizeof(addr.sun_path) - 2;
    memcpy(addr.sun_path + 1, SHORTCUT_SOCKET_NAME, len);
    socklen_t slen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + len);
    if (connect(fd, (struct sockaddr *)&addr, slen) < 0) {
        close(fd);
        return 0;
    }
    shortcut_fd = fd;
    return 1;
}

static void shortcut_send(int code, int value) {
    if (!shortcut_connect()) return;
    char line[64];
    int n = snprintf(line, sizeof(line), "K %d %d\n", code, value);
    if (n <= 0) return;
    ssize_t sent = send(shortcut_fd, line, (size_t)n, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        close(shortcut_fd);
        shortcut_fd = -1;
    }
}

static int combo_code_valid(int code) {
    return (code > 0 && code <= KEY_MAX)
            || code == COMBO_LEFT_JOY || code == COMBO_RIGHT_JOY;
}

static int combo_is_modifier(int code) {
    for (int i = 0; i < combo_count; i++) {
        if (combos[i].modifier == code) return 1;
    }
    return 0;
}

static int combo_is_first_gate(int code) {
    for (int i = 0; i < combo_count; i++) {
        if (combos[i].modifier == code) return 1;
    }
    return 0;
}

static void combo_release_modifier(int code) {
    int deact = -1;
    for (int i = 0; i < combo_count; i++) {
        if (combos[i].modifier != code) continue;
        if (combos[i].gesture_armed && !combos[i].gesture_tapped) {
            if (right_down) {
                right_release();
            } else {
                combo_tap(i);
            }
        }
        if (combo_stick_index == i) combo_stick_release_handover(i);
        combos[i].gesture_armed = 0;
        combos[i].gesture_moved = 0;
        combos[i].gesture_tapped = 0;
        if (!combos[i].active) continue;
        combos[i].active = 0;
        if (combo_stick_index == i) deact = i;
    }
    if (deact >= 0) combo_stick_deactivate(deact);
}

static int combo_activate(int button) {
    if (!combo_code_valid(button)) return 0;
    for (int i = 0; i < combo_count; i++) {
        if (combos[i].active || combos[i].button != button) continue;
        if (!combo_mod_down[combos[i].modifier]) continue;
        combos[i].active = 1;
        combo_stick_activate(i);
        return 1;
    }
    return 0;
}

static void combo_arm_first_gate(int modifier) {
    for (int i = 0; i < combo_count; i++) {
        if (combos[i].modifier != modifier) continue;
        if (!combo_mod_down[modifier]) continue;
        combos[i].gesture_armed = 1;
        combos[i].gesture_moved = combo_right_joy_down;
        combos[i].gesture_tapped = 0;
        combos[i].active = 1;
        combo_stick_claim(i);
        return;
    }
}

static void combo_tap(int index) {
    if (index < 0 || index >= combo_count) return;
    int slot = free_slot();
    if (slot < 0) return;
    int x, y;
    touch_coords(combos[index].x, combos[index].y, &x, &y);
    int tracking = next_tracking++;
    if (tracking > 65534) next_tracking = 1;
    int had_touch = any_touch() > 0;
    if (!had_touch) emit(EV_KEY, BTN_TOUCH, 1);
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tracking);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 140);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
    usleep(70000);
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    sync_report();
    if (!had_touch && any_touch() == 0) {
        emit(EV_KEY, BTN_TOUCH, 0);
        sync_report();
    }
}

static int combo_active_for_button(int button) {
    for (int i = 0; i < combo_count; i++) {
        if (combos[i].active && combos[i].button == button) return i;
    }
    return -1;
}

static void combo_joystick_state(int code, int down) {
    if (code == COMBO_RIGHT_JOY) {
        if (down) {
            // The right stick was deflected while the first button was held.
            for (int i = 0; i < combo_count; i++) {
                if (combos[i].gesture_armed) combos[i].gesture_moved = 1;
            }
            if (combo_is_modifier(code)) {
                combo_mod_down[code] = 1;
                combo_arm_first_gate(code);
            }
        } else {
            if (right_down) right_release();
            for (int i = 0; i < combo_count; i++) {
                if (!combos[i].gesture_armed) continue;
                if (combo_stick_index == i) combos[i].gesture_tapped = 1;
                combos[i].gesture_moved = 0;
            }
            if (combo_is_modifier(code)) {
                combo_mod_down[code] = 0;
                combo_release_modifier(code);
            }
        }
        return;
    }
    if (down) {
        if (combo_active_for_button(code) >= 0) return;
        if (combo_is_modifier(code)) {
            combo_mod_down[code] = 1;
            combo_arm_first_gate(code);
        }
        combo_activate(code);
    } else {
        if (combo_is_modifier(code)) {
            combo_mod_down[code] = 0;
            combo_release_modifier(code);
        }
        int ci = combo_active_for_button(code);
        if (ci >= 0) {
            combos[ci].active = 0;
            combo_stick_deactivate(ci);
        }
    }
}

static void combo_update_joysticks(void) {
    float lx = (float)stick_raw_x / 32767.0f;
    float ly = (float)stick_raw_y / 32767.0f;
    int left = stick_enabled && hypotf(lx, ly) >= 0.12f;
    if (left != combo_left_joy_down) {
        combo_left_joy_down = left;
        combo_joystick_state(COMBO_LEFT_JOY, left);
    }
    float rx = (float)right_raw_x / 32767.0f;
    float ry = (float)right_raw_y / 32767.0f;
    int right = right_mode != RIGHT_OFF && hypotf(rx, ry) >= 0.06f;
    if (right != combo_right_joy_down) {
        combo_right_joy_down = right;
        combo_joystick_state(COMBO_RIGHT_JOY, right);
    }
}

static int free_slot(void) {
    int reserved = (stick_enabled ? 1 : 0) + (right_touch_enabled() ? 1 : 0);
    int limit = MAX_TOUCH - reserved;
    for (int i = 0; i < limit; i++) {
        int used = 0;
        for (int c = 0; c <= KEY_MAX; c++) {
            if (maps[c].down && maps[c].slot == i) {
                used = 1;
                break;
            }
        }
        if (!used) return i;
    }
    return -1;
}

static int active_touches(void) {
    int n = 0;
    for (int c = 0; c <= KEY_MAX; c++) if (maps[c].down) n++;
    return n;
}

static int any_touch(void) {
    return active_touches() + (stick_down ? 1 : 0) + (right_down ? 1 : 0);
}

static void touch_coords(float sx, float sy, int *raw_x, int *raw_y) {
    // This panel's internal touch device is portrait (1080x1920) and Android
    // rotates it into the 1920x1080 logical display. Convert the editor's
    // logical screen coordinates back into that raw portrait space.
    float x = (1.0f - sy) * 32767.0f;
    float y = sx * 32767.0f;
    if (x < 0.f) x = 0.f;
    if (x > 32767.f) x = 32767.f;
    if (y < 0.f) y = 0.f;
    if (y > 32767.f) y = 32767.f;
    *raw_x = (int)x;
    *raw_y = (int)y;
}

static void touch_down(int code) {
    struct map_entry *m = &maps[code];
    if (m->down) return;
    int slot = free_slot();
    if (slot < 0) return;
    int x, y;
    touch_coords(m->x, m->y, &x, &y);
    int tracking = next_tracking++;
    if (tracking > 65534) next_tracking = 1;
    if (any_touch() == 0) emit(EV_KEY, BTN_TOUCH, 1);
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tracking);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 140);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
    m->slot = slot;
    m->down = 1;
}

static void touch_up(int code) {
    struct map_entry *m = &maps[code];
    if (!m->down) return;
    emit(EV_ABS, ABS_MT_SLOT, m->slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    sync_report();
    m->down = 0;
    m->slot = -1;
    if (any_touch() == 0) {
        emit(EV_KEY, BTN_TOUCH, 0);
        sync_report();
    }
}

static void stick_release(void) {
    if (!stick_down) return;
    emit(EV_ABS, ABS_MT_SLOT, stick_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    sync_report();
    stick_down = 0;
    if (any_touch() == 0) {
        emit(EV_KEY, BTN_TOUCH, 0);
        sync_report();
    }
}

static void stick_start(void);

static void stick_emit_position(void) {
    float nx = (float)stick_raw_x / 32767.0f;
    float ny = (float)stick_raw_y / 32767.0f;
    float mag = sqrtf(nx * nx + ny * ny);
    float dead = 0.11f;
    if (mag < dead) {
        if (stick_down) stick_release();
        return;
    }
    if (!stick_down) stick_start();
    if (!stick_down) return;
    if (mag > 1.f) mag = 1.f;
    nx = nx / mag * ((mag - dead) / (1.f - dead));
    ny = ny / mag * ((mag - dead) / (1.f - dead));
    if (nx > 1.f) nx = 1.f;
    if (nx < -1.f) nx = -1.f;
    if (ny > 1.f) ny = 1.f;
    if (ny < -1.f) ny = -1.f;
    float rx = stick_radius * 9.0f / 16.0f;
    float ry = stick_radius;
    float sx = stick_cx + nx * rx;
    float sy = stick_cy + ny * ry;
    int x, y;
    touch_coords(sx, sy, &x, &y);
    emit(EV_ABS, ABS_MT_SLOT, stick_slot);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 128);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
}

static void stick_start(void) {
    if (!stick_enabled || stick_down) return;
    stick_slot = MAX_TOUCH - 1;
    int tracking = next_tracking++;
    if (tracking > 65534) next_tracking = 1;
    emit(EV_KEY, BTN_TOUCH, 1);
    emit(EV_ABS, ABS_MT_SLOT, stick_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tracking);
    int x, y;
    touch_coords(stick_cx, stick_cy, &x, &y);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 128);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
    stick_down = 1;
}

static void right_release(void) {
    if (!right_down) return;
    emit(EV_ABS, ABS_MT_SLOT, right_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    sync_report();
    right_down = 0;
    if (any_touch() == 0) {
        emit(EV_KEY, BTN_TOUCH, 0);
        sync_report();
    }
}

static void right_start_at(float sx, float sy) {
    if ((!right_touch_enabled() && combo_stick_index < 0) || right_down) return;
    right_slot = MAX_TOUCH - 1 - (stick_enabled ? 1 : 0);
    int tracking = next_tracking++;
    if (tracking > 65534) next_tracking = 1;
    if (any_touch() == 0) emit(EV_KEY, BTN_TOUCH, 1);
    emit(EV_ABS, ABS_MT_SLOT, right_slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, tracking);
    int x, y;
    touch_coords(sx, sy, &x, &y);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 128);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
    right_down = 1;
}

static void right_start(void) {
    right_start_at(active_right_cx(), active_right_cy());
}

static void right_emit_position(void) {
    float nx = (float)right_raw_x / 32767.0f;
    float ny = (float)right_raw_y / 32767.0f;
    float mag = sqrtf(nx * nx + ny * ny);
    float dead = r3_zone_active() ? 0.035f : 0.11f;
    if (mag < dead) {
        if (right_down) right_release();
        return;
    }
    if (!right_down) right_start();
    if (!right_down) return;
    if (mag > 1.f) mag = 1.f;
    nx = nx / mag * ((mag - dead) / (1.f - dead));
    ny = ny / mag * ((mag - dead) / (1.f - dead));
    if (nx > 1.f) nx = 1.f;
    if (nx < -1.f) nx = -1.f;
    if (ny > 1.f) ny = 1.f;
    if (ny < -1.f) ny = -1.f;
    float radius = active_right_radius();
    float rx = radius * 9.0f / 16.0f;
    float ry = radius;
    float sx = active_right_cx() + nx * rx;
    float sy = active_right_cy() + ny * ry;
    int x, y;
    touch_coords(sx, sy, &x, &y);
    emit(EV_ABS, ABS_MT_SLOT, right_slot);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 128);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
}

static void combo_stick_activate(int index) {
    if (index < 0 || index >= combo_count) return;
    combo_stick_index = index;
    if (r3_zone_active()) return;
    if (right_down) right_release();
    aim_input = 0.f;
    aim_speed = 0.f;
    mouse_accum_x = 0.f;
    mouse_accum_y = 0.f;
    right_start_at(combos[index].x, combos[index].y);
    float mag = hypotf((float)right_raw_x / 32767.0f,
            (float)right_raw_y / 32767.0f);
    if (mag >= 0.11f) right_emit_position();
}

static void combo_stick_deactivate(int index) {
    if (combo_stick_index != index) return;
    if (right_down) right_release();
    if (!r3_zone_active()) {
        // Port R3's release tap: let go of the held direction, then tap centre.
        right_start_at(combos[index].x, combos[index].y);
        right_release();
    }
    combo_stick_index = -1;
    for (int i = 0; i < combo_count; i++) {
        if (i != index && combos[i].active) {
            combo_stick_activate(i);
            return;
        }
    }
    if (r3_zone_active()) return;
    if (right_mode == RIGHT_AIM) {
        aim_last_ms = 0;
        aim_tick();
    } else if (right_mode != RIGHT_MOUSE) {
        right_emit_position();
    }
}

static void combo_stick_claim(int index) {
    if (index < 0 || index >= combo_count) return;
    combo_stick_index = index;
    if (r3_zone_active()) return;
    if (right_down) right_release();
    aim_input = 0.f;
    aim_speed = 0.f;
    mouse_accum_x = 0.f;
    mouse_accum_y = 0.f;
    float mag = hypotf((float)right_raw_x / 32767.0f,
            (float)right_raw_y / 32767.0f);
    if (mag >= 0.06f) {
        right_emit_position();
        combos[index].gesture_moved = 1;
    }
}

static void combo_stick_release_handover(int index) {
    if (combo_stick_index != index) return;
    if (right_down) right_release();
    combos[index].active = 0;
    combo_stick_index = -1;
    if (r3_zone_active()) return;
    if (right_mode == RIGHT_AIM) {
        aim_last_ms = 0;
        aim_tick();
    } else if (right_mode != RIGHT_MOUSE) {
        right_emit_position();
    }
}

static void aim_tick(void) {
    if (right_mode != RIGHT_AIM) return;
    if (combo_stick_active() && !r3_zone_active()) return;
    if (!right_gate_open()) {
        aim_input = 0.f;
        aim_speed = 0.f;
        if (right_down) right_release();
        return;
    }
    if (r3_zone_active() && fabsf((float)right_raw_x / 32767.0f) > 0.11f) {
        r3_used = 1;
    }
    long long now = monotonic_ms();
    long long elapsed = now - aim_last_ms;
    if (elapsed < 4LL) return;
    if (elapsed > 20LL) elapsed = 20LL;
    aim_last_ms = now;
    float dt = (float)elapsed;

    float rx = (float)right_raw_x / 32767.0f;
    const float dead = 0.10f;
    float target = 0.f;
    if (fabsf(rx) > dead) {
        target = rx;
        target = (target < 0.f ? -1.f : 1.f)
                * ((fabsf(target) - dead) / (1.f - dead));
    }

    // Low-pass the stick input, then integrate a smoothed angular swipe speed.
    // This removes the stair-step feel of raw 120 Hz axis reports while keeping
    // a full sweep responsive at full deflection.
    float inputBlend = 1.f - expf(-dt / 10.f);
    aim_input += (target - aim_input) * inputBlend;
    float desired = aim_input * 0.00028f;
    float speedBlend = 1.f - expf(-dt / 6.f);
    aim_speed += (desired - aim_speed) * speedBlend;

    if (!right_down && fabsf(aim_input) < 0.012f
            && fabsf(aim_speed) < 0.000008f) {
        aim_speed = 0.f;
        return;
    }
    if (!right_down) {
        aim_x = active_right_cx();
        right_start_at(aim_x, active_right_cy());
        if (!right_down) return;
    }

    aim_x += aim_speed * dt;
    float minX = right_cx - right_radius;
    float maxX = right_cx + right_radius;
    if (aim_x > maxX) {
        // Finish this horizontal swipe at the edge, lift and restart at the
        // opposite edge. There is no visible joystick base and the hero keeps
        // the facing angle while the new swipe begins.
        right_release();
        aim_x = minX;
        right_start_at(aim_x, right_cy);
    } else if (aim_x < minX) {
        right_release();
        aim_x = maxX;
        right_start_at(aim_x, right_cy);
    }
    if (!right_down) return;
    int x, y;
    touch_coords(aim_x, active_right_cy(), &x, &y);
    emit(EV_ABS, ABS_MT_SLOT, right_slot);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    emit(EV_ABS, ABS_MT_PRESSURE, 128);
    emit(EV_ABS, ABS_MT_TOUCH_MAJOR, 18);
    sync_report();
    if (aim_debug) {
        fprintf(stderr, "AIM t=%lld dt=%.1f raw=%d input=%.4f speed=%.6f x=%.5f\n",
                now, dt, right_raw_x, aim_input, aim_speed, aim_x);
    }

    if (fabsf(aim_input) < 0.008f && fabsf(aim_speed) < 0.000004f) {
        // The swipe has fully coasted to rest. The next right-stick movement
        // starts a fresh swipe; the game keeps the last hero facing.
        right_release();
        aim_input = 0.f;
        aim_speed = 0.f;
    }
}

static void mouse_emit(int type, int code, int value) {
    if (mouse_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    ssize_t ignored = write(mouse_fd, &ev, sizeof(ev));
    (void)ignored;
}

static void mouse_sync(void) {
    mouse_emit(EV_SYN, SYN_REPORT, 0);
}

static long long monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void mouse_tick(void) {
    if (mouse_fd < 0 || right_mode != RIGHT_MOUSE) return;
    if (combo_stick_active() && !r3_zone_active()) return;
    if (!right_gate_open()) return;
    if (right_raw_x == 0 && right_raw_y == 0) return;
    long long now = monotonic_ms();
    if (now - last_mouse_ms < 15LL) return;
    last_mouse_ms = now;
    mouse_accum_x += (float)right_raw_x * 0.00110f;
    mouse_accum_y += (float)right_raw_y * 0.00110f;
    int sx = (int)mouse_accum_x;
    int sy = (int)mouse_accum_y;
    mouse_accum_x -= sx;
    mouse_accum_y -= sy;
    if (sx == 0 && sy == 0) return;
    if (sx != 0) mouse_emit(EV_REL, REL_X, sx);
    if (sy != 0) mouse_emit(EV_REL, REL_Y, sy);
    mouse_sync();
}

static void mouse_button(int button, int down) {
    if (mouse_fd < 0) return;
    if (button == BTN_LEFT) mouse_left_down = down;
    if (button == BTN_RIGHT) mouse_right_down = down;
    mouse_emit(EV_KEY, button, down ? 1 : 0);
    mouse_sync();
}

static void release_all(void) {
    for (int c = 0; c <= KEY_MAX; c++) if (maps[c].down) touch_up(c);
    for (int i = 0; i < combo_count; i++) combos[i].active = 0;
    combo_stick_index = -1;
    combo_left_joy_down = 0;
    combo_right_joy_down = 0;
    stick_release();
    right_release();
    if (mouse_left_down) mouse_button(BTN_LEFT, 0);
    if (mouse_right_down) mouse_button(BTN_RIGHT, 0);
}

static void set_virtual_key(int code, int down) {
    if (down) {
        if (combo_active_for_button(code) >= 0) return;
        if (combo_is_modifier(code)) {
            combo_mod_down[code] = 1;
            combo_arm_first_gate(code);
        }
        if (combo_activate(code)) return;
    } else {
        if (combo_is_modifier(code)) {
            combo_mod_down[code] = 0;
            combo_release_modifier(code);
        }
        int ci = combo_active_for_button(code);
        if (ci >= 0) {
            combos[ci].active = 0;
            combo_stick_deactivate(ci);
            return;
        }
    }
    if (combo_is_first_gate(code)) return;
    if (map_for(code) < 0) return;
    if (down) touch_down(code);
    else touch_up(code);
}

static void update_hat(void) {
    if (hat_x < 0) { set_virtual_key(546, 1); set_virtual_key(547, 0); }
    else if (hat_x > 0) { set_virtual_key(546, 0); set_virtual_key(547, 1); }
    else { set_virtual_key(546, 0); set_virtual_key(547, 0); }
    if (hat_y < 0) { set_virtual_key(544, 1); set_virtual_key(545, 0); }
    else if (hat_y > 0) { set_virtual_key(544, 0); set_virtual_key(545, 1); }
    else { set_virtual_key(544, 0); set_virtual_key(545, 0); }
}

static void abs_setup(int fd, int code, int min, int max) {
    struct uinput_abs_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.code = (uint16_t)code;
    setup.absinfo.minimum = min;
    setup.absinfo.maximum = max;
    setup.absinfo.fuzz = 0;
    setup.absinfo.flat = 0;
    ioctl(fd, UI_ABS_SETUP, &setup);
}

static int create_touchscreen(void) {
    ui_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ui_fd < 0) return 0;
    ioctl(ui_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(ui_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(ui_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(ui_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(ui_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    abs_setup(ui_fd, ABS_MT_SLOT, 0, MAX_TOUCH - 1);
    abs_setup(ui_fd, ABS_MT_TRACKING_ID, 0, 65535);
    abs_setup(ui_fd, ABS_MT_POSITION_X, 0, 32767);
    abs_setup(ui_fd, ABS_MT_POSITION_Y, 0, 32767);
    abs_setup(ui_fd, ABS_MT_PRESSURE, 0, 255);
    abs_setup(ui_fd, ABS_MT_TOUCH_MAJOR, 0, 255);
    abs_setup(ui_fd, ABS_X, 0, 32767);
    abs_setup(ui_fd, ABS_Y, 0, 32767);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x2020;
    setup.id.product = 0x0222;
    setup.id.version = 0x0001;
    snprintf(setup.name, sizeof(setup.name), "Aurora Virtual Touch");
    if (ioctl(ui_fd, UI_DEV_SETUP, &setup) < 0
            || ioctl(ui_fd, UI_DEV_CREATE) < 0) {
        close(ui_fd);
        ui_fd = -1;
        return 0;
    }
    return 1;
}

static int create_mouse(void) {
    mouse_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (mouse_fd < 0) return 0;
    ioctl(mouse_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(mouse_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(mouse_fd, UI_SET_EVBIT, EV_REL);
    ioctl(mouse_fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(mouse_fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(mouse_fd, UI_SET_RELBIT, REL_X);
    ioctl(mouse_fd, UI_SET_RELBIT, REL_Y);
    ioctl(mouse_fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x2020;
    setup.id.product = 0x0223;
    setup.id.version = 0x0001;
    snprintf(setup.name, sizeof(setup.name), "Aurora Virtual Mouse");
    if (ioctl(mouse_fd, UI_DEV_SETUP, &setup) < 0
            || ioctl(mouse_fd, UI_DEV_CREATE) < 0) {
        close(mouse_fd);
        mouse_fd = -1;
        return 0;
    }
    return 1;
}

static int find_controller(char *out, size_t size) {
    FILE *f = popen("getevent -lp 2>/dev/null", "r");
    if (!f) return 0;
    char line[512];
    char current[256] = {0};
    int found = 0;
    while (fgets(line, sizeof(line), f)) {
        char *t = line;
        while (*t == ' ' || *t == '\t') t++;
        if (!strncmp(t, "add device", 10)) {
            char *slash = strchr(t, '/');
            if (slash) {
                snprintf(current, sizeof(current), "%s", slash);
                char *nl = strchr(current, '\n');
                if (nl) *nl = 0;
            }
        } else if (!strncmp(t, "name:", 5) && current[0]) {
            char *name = t + 5;
            while (*name == ' ' || *name == '\t') name++;
            if (strcasestr(name, "odin controller")
                    || (strcasestr(name, "controller")
                    && !strcasestr(name, "aurora"))) {
                snprintf(out, size, "%s", current);
                found = 1;
                break;
            }
        }
    }
    pclose(f);
    return found;
}

static int load_config(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[256];
    int count = 0;
    memset(maps, 0, sizeof(maps));
    for (int i = 0; i <= KEY_MAX; i++) maps[i].slot = -1;
    shortcut_count = 0;
    while (fgets(line, sizeof(line), f)) {
        int sc;
        if (sscanf(line, "shortcut %d", &sc) == 1
                && sc > 0 && sc <= KEY_MAX && shortcut_count < MAX_SHORTCUTS) {
            shortcut_codes[shortcut_count++] = sc;
            continue;
        }
        int code;
        float x;
        float y;
        if (sscanf(line, "map %d %f %f", &code, &x, &y) == 3
                && code > 0 && code <= KEY_MAX) {
            maps[code].code = code;
            maps[code].x = x < 0.f ? 0.f : (x > 1.f ? 1.f : x);
            maps[code].y = y < 0.f ? 0.f : (y > 1.f ? 1.f : y);
            maps[code].slot = -1;
            maps[code].down = 0;
            count++;
        } else {
            int cm, cb;
            float ccx, ccy, crad = 0.f;
            int comboFields = sscanf(line, "combo %d %d %f %f %f",
                    &cm, &cb, &ccx, &ccy, &crad);
            if (comboFields >= 4 && combo_code_valid(cm) && combo_code_valid(cb)
                    && combo_count < MAX_COMBOS) {
                combos[combo_count].modifier = cm;
                combos[combo_count].button = cb;
                combos[combo_count].x = ccx < 0.f ? 0.f : (ccx > 1.f ? 1.f : ccx);
                combos[combo_count].y = ccy < 0.f ? 0.f : (ccy > 1.f ? 1.f : ccy);
                float rr = comboFields >= 5 ? crad : COMBO_STICK_RADIUS;
                combos[combo_count].radius = rr < 0.06f ? 0.06f
                        : (rr > 0.30f ? 0.30f : rr);
                combos[combo_count].active = 0;
                combo_count++;
                continue;
            }
            int ax, ay;
            float cx, cy, radius;
            if (sscanf(line, "stick %d %d %f %f %f", &ax, &ay, &cx, &cy, &radius) == 5) {
                stick_enabled = 1;
                stick_axis_x = ax;
                stick_axis_y = ay;
                stick_cx = cx < 0.f ? 0.f : (cx > 1.f ? 1.f : cx);
                stick_cy = cy < 0.f ? 0.f : (cy > 1.f ? 1.f : cy);
                stick_radius = radius < 0.06f ? 0.06f : (radius > 0.24f ? 0.24f : radius);
            } else {
                int mode;
                if (sscanf(line, "rstick %d %d %d %f %f %f",
                        &mode, &ax, &ay, &cx, &cy, &radius) == 6) {
                    right_mode = mode < 1 || mode > RIGHT_AIM ? 0 : mode;
                    right_axis_x = ax;
                    right_axis_y = ay;
                    right_cx = cx < 0.f ? 0.f : (cx > 1.f ? 1.f : cx);
                    right_cy = cy < 0.f ? 0.f : (cy > 1.f ? 1.f : cy);
                    right_radius = radius < 0.06f ? 0.06f : (radius > 0.24f ? 0.24f : radius);
                } else {
                    int hcode;
                    float hx, hy;
                    if (sscanf(line, "rholdmap %d %f %f", &hcode, &hx, &hy) == 3
                            && hcode > 0 && hcode <= KEY_MAX) {
                        maps[hcode].code = hcode;
                        maps[hcode].x = hx < 0.f ? 0.f : (hx > 1.f ? 1.f : hx);
                        maps[hcode].y = hy < 0.f ? 0.f : (hy > 1.f ? 1.f : hy);
                        maps[hcode].slot = -1;
                        maps[hcode].down = 0;
                        count++;
                        right_hold_enabled = 1;
                        right_hold_code = hcode;
                        right_held = 0;
                    } else {
                        int holdCode;
                        float rcx2, rcy2, rrad2;
                        if (sscanf(line, "r3stick %f %f %f", &rcx2, &rcy2, &rrad2) == 3) {
                            r3_stick_enabled = 1;
                            r3_cx = rcx2 < 0.f ? 0.f : (rcx2 > 1.f ? 1.f : rcx2);
                            r3_cy = rcy2 < 0.f ? 0.f : (rcy2 > 1.f ? 1.f : rcy2);
                            r3_radius = rrad2 < 0.06f ? 0.06f
                                    : (rrad2 > 0.24f ? 0.24f : rrad2);
                        } else if (sscanf(line, "rhold %d", &holdCode) == 1 && holdCode > 0) {
                            right_hold_enabled = 1;
                            right_hold_code = holdCode;
                            right_held = 0;
                        }
                    }
                }
            }
        }
    }
    fclose(f);
    for (int i = 0; i <= COMBO_CODE_MAX; i++) combo_mod_down[i] = 0;
    for (int i = 0; i < combo_count; i++) {
        combos[i].active = 0;
        combos[i].down = 0;
        combos[i].slot = -1;
        combos[i].gesture_armed = 0;
        combos[i].gesture_moved = 0;
        combos[i].gesture_tapped = 0;
    }
    combo_stick_index = -1;
    map_count = count;
    return count > 0 || combo_count > 0 || stick_enabled || right_mode != RIGHT_OFF;
}

static void write_pid(void) {
    if (!pid_path[0]) return;
    FILE *f = fopen(pid_path, "w");
    if (!f) return;
    fprintf(f, "%d\n", getpid());
    fclose(f);
}

static void remove_pid(void) {
    if (pid_path[0]) unlink(pid_path);
}

static void on_abs(int code, int value) {
    if (code == right_axis_x || code == right_axis_y) {
        if (code == right_axis_x) right_raw_x = value;
        else right_raw_y = value;
        combo_update_joysticks();
        if (combo_stick_active() && !r3_zone_active()) {
            right_emit_position();
            return;
        }
    }
    if (right_mode == RIGHT_MOUSE && code == right_axis_x) {
        right_raw_x = value;
        if (!right_gate_open()) return;
    } else if (right_mode == RIGHT_MOUSE && code == right_axis_y) {
        right_raw_y = value;
        if (!right_gate_open()) return;
    } else if (right_mode == RIGHT_AIM && code == right_axis_x) {
        right_raw_x = value;
        if (aim_debug) {
            fprintf(stderr, "STICK t=%lld axis=%d value=%d\n",
                    monotonic_ms(), code, value);
        }
        if (!right_gate_open()) return;
        aim_tick();
    } else if (right_mode == RIGHT_AIM && code == right_axis_y) {
        right_raw_y = value;
        if (aim_debug) {
            fprintf(stderr, "STICK t=%lld axis=%d value=%d\n",
                    monotonic_ms(), code, value);
        }
        if (!right_gate_open()) return;
    } else if (right_touch_enabled() && code == right_axis_x) {
        right_raw_x = value;
        if (!right_gate_open()) return;
        right_emit_position();
    } else if (right_touch_enabled() && code == right_axis_y) {
        right_raw_y = value;
        if (!right_gate_open()) return;
        right_emit_position();
    } else if (stick_enabled && code == stick_axis_x) {
        stick_raw_x = value;
        stick_emit_position();
    } else if (stick_enabled && code == stick_axis_y) {
        stick_raw_y = value;
        stick_emit_position();
    } else if (code == ABS_HAT0X) {
        hat_x = value < 0 ? -1 : (value > 0 ? 1 : 0);
        update_hat();
    } else if (code == ABS_HAT0Y) {
        hat_y = value < 0 ? -1 : (value > 0 ? 1 : 0);
        update_hat();
    }
}

int main(int argc, char **argv) {
    char config_path[512] = {0};
    char device[256] = {0};
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--config") && i + 1 < argc) {
            snprintf(config_path, sizeof(config_path), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--pid") && i + 1 < argc) {
            snprintf(pid_path, sizeof(pid_path), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--device") && i + 1 < argc) {
            snprintf(device, sizeof(device), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--client") && i + 1 < argc) {
            client_pid = atoi(argv[++i]);
        }
    }
    if (!config_path[0] || !load_config(config_path)) {
        fprintf(stderr, "invalid config\n");
        return 2;
    }
    if (!device[0] && !find_controller(device, sizeof(device))) {
        fprintf(stderr, "controller not found\n");
        return 3;
    }
    aim_debug = access("/data/local/tmp/aurora_aim_debug", F_OK) == 0;
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    src_fd = open(device, O_RDONLY | O_CLOEXEC);
    if (src_fd < 0) {
        fprintf(stderr, "open %s: %s\n", device, strerror(errno));
        return 4;
    }
    if (ioctl(src_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "grab: %s\n", strerror(errno));
        close(src_fd);
        return 5;
    }
    if (!create_touchscreen()) {
        fprintf(stderr, "uinput: %s\n", strerror(errno));
        ioctl(src_fd, EVIOCGRAB, 0);
        close(src_fd);
        return 6;
    }
    if (right_mode == RIGHT_MOUSE && !create_mouse()) {
        fprintf(stderr, "virtual mouse unavailable: %s\n", strerror(errno));
    }
    write_pid();
    fprintf(stderr, "touch mapper ready (%d maps%s%s)\n", map_count,
            stick_enabled ? ", analog stick" : "",
            right_mode == RIGHT_MOUSE ? ", mouse"
                    : (right_mode == RIGHT_AIM ? ", swipe aim"
                    : (right_mode ? ", right stick" : "")));
    if (shortcut_count) shortcut_connect();
    if (right_hold_enabled) fprintf(stderr, "R3 gates right stick\n");
    if (r3_stick_enabled) fprintf(stderr, "R3 secondary stick enabled\n");
    if (combo_count) fprintf(stderr, "%d combos loaded\n", combo_count);
    if (aim_debug) fprintf(stderr, "aim debug enabled\n");

    struct pollfd pfd;
    pfd.fd = src_fd;
    pfd.events = POLLIN;
    while (!stop_requested) {
        if (client_pid > 0 && kill(client_pid, 0) < 0 && errno == ESRCH) {
            fprintf(stderr, "client process gone; exiting\n");
            break;
        }
        int pr = poll(&pfd, 1,
                right_mode == RIGHT_MOUSE || right_mode == RIGHT_AIM ? 4 : 200);
        if (pr > 0) {
            struct input_event ev[64];
            ssize_t n = read(src_fd, ev, sizeof(ev));
            if (n > 0) {
                size_t count = (size_t)n / sizeof(ev[0]);
                for (size_t i = 0; i < count; i++) {
                    if (ev[i].type != EV_KEY) continue;
                    int mc = ev[i].code;
                    if (shortcut_is_reserved(mc)) continue;
                    if (!combo_is_modifier(mc)) continue;
                    if (ev[i].value == 1) {
                        combo_mod_down[mc] = 1;
                        combo_arm_first_gate(mc);
                    } else if (ev[i].value == 0) {
                        combo_mod_down[mc] = 0;
                        combo_release_modifier(mc);
                    }
                }
              for (int pass = 0; pass < 2; pass++) {
                for (size_t i = 0; i < count; i++) {
                    if (pass == 0 && ev[i].type != EV_KEY) continue;
                    if (pass == 1 && ev[i].type != EV_ABS) continue;
                    if (ev[i].type == EV_KEY) {
                        int code = ev[i].code;
                        if (shortcut_is_reserved(code)) {
                            if (ev[i].value == 1 || ev[i].value == 0) {
                                shortcut_send(code, ev[i].value);
                            }
                            continue;
                        }
                        if (r3_stick_enabled && code == BTN_THUMBR) {
                            if (ev[i].value == 1) {
                                if (combo_activate(code)) continue;
                                r3_held = 1;
                                r3_used = 0;
                                if (right_down) right_release();
                                aim_input = 0.f;
                                aim_speed = 0.f;
                                float mag = hypotf(
                                        (float)right_raw_x / 32767.0f,
                                        (float)right_raw_y / 32767.0f);
                                if (right_mode == RIGHT_AIM) {
                                    if (mag >= 0.035f) {
                                        r3_used = 1;
                                        aim_last_ms = 0;
                                        aim_tick();
                                    }
                                } else if (right_touch_enabled() && mag >= 0.035f) {
                                    r3_used = 1;
                                    right_emit_position();
                                }
                            } else if (ev[i].value == 0) {
                                int r3ci = combo_active_for_button(code);
                                if (r3ci >= 0) {
                                    combos[r3ci].active = 0;
                                    combo_stick_deactivate(r3ci);
                                    continue;
                                }
                                if (right_down) right_release();
                                if (right_touch_enabled()
                                        && right_mode != RIGHT_MOUSE) {
                                    // Trigger R3 on release, never on press.
                                    // A held secondary joystick is released first,
                                    // then the R3 centre is tapped.
                                    right_start();
                                    right_release();
                                }
                                r3_held = 0;
                                r3_used = 0;
                                aim_input = 0.f;
                                aim_speed = 0.f;
                                if (right_mode == RIGHT_AIM) {
                                    aim_last_ms = 0;
                                    aim_tick();
                                } else if (right_touch_enabled()) {
                                    right_emit_position();
                                }
                            }
                            continue;
                        }
                        if (right_hold_enabled && code == right_hold_code) {
                            if (ev[i].value == 1) {
                                right_held = 1;
                                if (map_for(code) >= 0) touch_down(code);
                                if (right_mode == RIGHT_MOUSE) {
                                    mouse_tick();
                                } else if (right_mode == RIGHT_AIM) {
                                    aim_last_ms = 0;
                                    aim_tick();
                                } else if (right_touch_enabled()) {
                                    right_emit_position();
                                }
                            } else if (ev[i].value == 0) {
                                right_held = 0;
                                if (map_for(code) >= 0) touch_up(code);
                                if (right_down) right_release();
                                aim_input = 0.f;
                                aim_speed = 0.f;
                                mouse_accum_x = 0.f;
                                mouse_accum_y = 0.f;
                            }
                            continue;
                        }
                        int ci = combo_active_for_button(code);
                        if (ev[i].value == 1) {
                            if (combo_activate(code)) continue;
                        } else if (ev[i].value == 0) {
                            if (ci >= 0) {
                                combos[ci].active = 0;
                                combo_stick_deactivate(ci);
                                continue;
                            }
                        }
                        if (right_mode == RIGHT_MOUSE && right_gate_open()
                                && (code == BTN_TR || code == BTN_TR2)) {
                            int button = code == BTN_TR ? BTN_LEFT : BTN_RIGHT;
                            if (ev[i].value == 1) mouse_button(button, 1);
                            else if (ev[i].value == 0) mouse_button(button, 0);
                        } else if (combo_is_first_gate(code)) {
                            // This button is the gate for a button + R JOY
                            // gesture combo; its action is the combo tap.
                        } else if (map_for(code) >= 0) {
                            if (ev[i].value == 1) touch_down(code);
                            else if (ev[i].value == 0) touch_up(code);
                        }
                    } else if (ev[i].type == EV_ABS) {
                        on_abs(ev[i].code, ev[i].value);
                        combo_update_joysticks();
                    }
                }
              }
            }
        }
        mouse_tick();
        aim_tick();
    }

    release_all();
    if (mouse_fd >= 0) {
        ioctl(mouse_fd, UI_DEV_DESTROY);
        close(mouse_fd);
        mouse_fd = -1;
    }
    if (ui_fd >= 0) {
        ioctl(ui_fd, UI_DEV_DESTROY);
        close(ui_fd);
        ui_fd = -1;
    }
    ioctl(src_fd, EVIOCGRAB, 0);
    close(src_fd);
    remove_pid();
    return 0;
}
