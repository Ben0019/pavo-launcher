#define _GNU_SOURCE
#include <errno.h>
#include <math.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

/*
 * Aurora controller remapper.
 *
 * The daemon owns the physical controller with EVIOCGRAB, applies the mapping
 * config and exposes one virtual controller through uinput.  Games and the
 * launcher therefore see a single controller whose buttons have already been
 * remapped, while analogue axes are copied through untouched.
 *
 * Config format (one directive per line, '#' comments):
 *   enabled=1
 *   capture=0
 *   map=304:305        source evdev code : target evdev code
 *   turbo=304          source button auto-repeats while held
 *   combo=310,311:305  simultaneous sources : shortcut target
 *   stick_enabled=1
 *   axis_x=0:-32767:32767  centre:min:max
 *   stick_deadzone_left=60   permille (stick_deadzone sets both)
 *   stick_deadzone_right=60
 *   stick_curve_left=100     percent (100 = linear)
 *   stick_curve_right=100
 *   stick_floor_left=0       minimum output, permille (anti-deadzone)
 *   stick_floor_right=0
 *   gate_enabled=1
 *   gate_left=1000,1010,...  per-angle gain, 32 points, permille
 *   gate_right=1000,1010,...
 */

#define MAX_COMBOS 16
#define MAX_COMBO_KEYS 4
#define COMBO_WINDOW_MS 70
#define TURBO_INTERVAL_MS 65
#define COMBO_PULSE_MS 45
#define CONFIG_POLL_MS 250

typedef struct {
    int keys[MAX_COMBO_KEYS];
    int count;
    int target;
} Combo;

#define STICK_AXES 6
#define GATE_POINTS 64
#define GATE_DEFAULT 1000

typedef struct {
    int enabled;
    int capture;
    int map[KEY_MAX + 1];
    int turbo[KEY_MAX + 1];
    int combo_member[KEY_MAX + 1];
    Combo combos[MAX_COMBOS];
    int combo_count;
    int stick_enabled;
    int stick_center[STICK_AXES];
    int stick_min[STICK_AXES];
    int stick_max[STICK_AXES];
    int stick_deadzone[2];
    int stick_curve[2];
    int stick_floor[2];
    int gate_enabled;
    int gate_gain[2][GATE_POINTS];
} Config;

typedef struct {
    int active;
    int source;
    int target;
    int phase;
    long long next_ms;
} TurboState;

typedef struct {
    int active;
    int combo;
    long long release_ms;
} ComboState;

static volatile sig_atomic_t stop_requested;
static Config config;
static int src_fd = -1;
static int ui_fd = -1;
static int server_fd = -1;
static int client_fd = -1;
static int swallow_all;
static int socket_enabled;
static int passthrough;
static char socket_name[108];
static char command_buffer[1024];
static size_t command_length;
static char config_path[512];
static char pid_path[512];
static char device[256];
static char park_path[512];
static char learn_path[512];
static char learn_reset_path[512];
static int learn_min[STICK_AXES];
static int learn_max[STICK_AXES];
static float learn_gate[2][GATE_POINTS];
static unsigned long learn_samples;
static int learn_reconnects;
static int stick_active[2];
static float learn_rest;
static float calm_hist[32];
static int calm_n;
static long long next_learn_write;
static char trace_path[512];
static dev_t src_dev_id;
static ino_t src_ino;
static long long next_source_check;
static FILE *trace_file;
static int grabbed = 1;
static int raw_axis[STICK_AXES];
static int last_axis[STICK_AXES];
static long long config_mtime;
static long long config_size;
static long long now_ms;
static int pressed[KEY_MAX + 1];
static int held[KEY_MAX + 1];
static int source_target[KEY_MAX + 1];
static int pending[KEY_MAX + 1];
static long long pending_at[KEY_MAX + 1];
static TurboState turbos[KEY_MAX + 1];
static ComboState combo_state[MAX_COMBOS];

static long long monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void on_signal(int sig) {
    (void)sig;
    stop_requested = 1;
}

static void write_pid(void) {
    if (pid_path[0] == 0) return;
    FILE *f = fopen(pid_path, "w");
    if (!f) return;
    fprintf(f, "%d\n", (int)getpid());
    fclose(f);
    chmod(pid_path, 0644);
}

static void remove_pid(void) {
    if (pid_path[0] == 0) return;
    unlink(pid_path);
}

static void release_all_targets(void);
static char *trim(char *s);
static int parse_int(const char *s, int *out);

static void socket_sendf(const char *fmt, ...) {
    if (client_fd < 0) return;
    char line[256];
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);
    if (n <= 0) return;
    if ((size_t)n > sizeof(line)) n = sizeof(line);
    ssize_t sent = send(client_fd, line, (size_t)n, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (sent < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
        close(client_fd);
        client_fd = -1;
        if (swallow_all) {
            swallow_all = 0;
            release_all_targets();
        }
    }
}

static void socket_send_key(int code, int value) {
    socket_sendf("K %d %d\n", code, value);
}

static void socket_send_axis(int code, int value) {
    // Right stick (ABS_Z/ABS_RZ) is forwarded as well: the quick controls panel
    // reserves it for the brightness and volume sliders.
    if (code == ABS_X || code == ABS_Y || code == ABS_HAT0X || code == ABS_HAT0Y
            || code == ABS_Z || code == ABS_RZ) {
        socket_sendf("A %d %d\n", code, value);
    }
}

static void close_client(void) {
    if (client_fd >= 0) {
        close(client_fd);
        client_fd = -1;
    }
    command_length = 0;
    if (swallow_all) {
        swallow_all = 0;
        release_all_targets();
    }
}

static void accept_client(void) {
    if (server_fd < 0 || client_fd >= 0) return;
    int fd = accept4(server_fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (fd < 0) return;
    client_fd = fd;
    command_length = 0;
    socket_sendf("HELLO 1\n");
}

static void handle_command(char *line) {
    while (*line == ' ' || *line == '\t') line++;
    char *end = line + strlen(line);
    while (end > line && (end[-1] == '\r' || end[-1] == '\n'
                          || end[-1] == ' ' || end[-1] == '\t')) {
        *--end = 0;
    }
    if (!strncmp(line, "swallow ", 8)) {
        int value = 0;
        if (parse_int(trim(line + 8), &value)) {
            if (value) {
                if (!swallow_all) {
                    release_all_targets();
                    swallow_all = 1;
                }
            } else {
                swallow_all = 0;
            }
        }
    }
}

static void read_commands(void) {
    if (client_fd < 0) return;
    char chunk[256];
    for (;;) {
        ssize_t n = recv(client_fd, chunk, sizeof(chunk), MSG_DONTWAIT);
        if (n == 0) {
            close_client();
            return;
        }
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
            close_client();
            return;
        }
        for (ssize_t i = 0; i < n; i++) {
            char c = chunk[i];
            if (c == '\n') {
                command_buffer[command_length] = 0;
                handle_command(command_buffer);
                command_length = 0;
            } else if (command_length + 1 < sizeof(command_buffer)) {
                command_buffer[command_length++] = c;
            }
        }
    }
}

static int open_socket_server(void) {
    if (socket_name[0] == 0) return 0;
    server_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (server_fd < 0) return 0;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = 0;
    size_t length = strlen(socket_name);
    if (length + 1 >= sizeof(addr.sun_path)) {
        close(server_fd);
        server_fd = -1;
        return 0;
    }
    memcpy(addr.sun_path + 1, socket_name, length);
    socklen_t addr_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length);
    if (bind(server_fd, (struct sockaddr *)&addr, addr_length) < 0
            || listen(server_fd, 1) < 0) {
        close(server_fd);
        server_fd = -1;
        return 0;
    }
    return 1;
}

static void emit_event(int type, int code, int value) {
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

static void emit_sync(void) {
    emit_event(EV_SYN, SYN_REPORT, 0);
}

static void output_key(int code, int down) {
    if (code < 0 || code > KEY_MAX) return;
    if (swallow_all) return;
    if (down) {
        if (held[code]++ == 0) emit_event(EV_KEY, code, 1);
    } else if (held[code] > 0) {
        if (--held[code] == 0) emit_event(EV_KEY, code, 0);
    }
    emit_sync();
}

static void release_all_targets(void) {
    for (int i = 0; i <= KEY_MAX; i++) {
        if (held[i] > 0) {
            held[i] = 0;
            emit_event(EV_KEY, i, 0);
        }
        source_target[i] = 0;
        pending[i] = 0;
        turbos[i].active = 0;
    }
    for (int i = 0; i < config.combo_count; i++) combo_state[i].active = 0;
    emit_sync();
}

static int combo_all_pressed(const Combo *c) {
    for (int i = 0; i < c->count; i++) {
        if (!pressed[c->keys[i]]) return false;
    }
    return true;
}

static void combo_activate(int index) {
    Combo *c = &config.combos[index];
    if (combo_state[index].active) return;
    combo_state[index].active = 1;
    combo_state[index].release_ms = now_ms + COMBO_PULSE_MS;
    output_key(c->target, 1);
    for (int i = 0; i < c->count; i++) {
        int key = c->keys[i];
        pending[key] = 0;
    }
}

static void apply_individual(int key) {
    int target = config.map[key];
    if (target < 0) target = key;
    if (config.turbo[key]) {
        TurboState *t = &turbos[key];
        t->active = 1;
        t->source = key;
        t->target = target;
        t->phase = 1;
        t->next_ms = now_ms + TURBO_INTERVAL_MS;
        output_key(target, 1);
        source_target[key] = target;
    } else {
        output_key(target, 1);
        source_target[key] = target;
    }
}

static void release_individual(int key, int quick_tap) {
    TurboState *t = &turbos[key];
    if (t->active) {
        if (t->phase) output_key(t->target, 0);
        t->active = 0;
        source_target[key] = 0;
        return;
    }
    int target = source_target[key];
    if (target == 0) target = config.map[key];
    if (target < 0) target = key;
    if (quick_tap) {
        output_key(target, 1);
        output_key(target, 0);
    } else {
        output_key(target, 0);
    }
    source_target[key] = 0;
}

static void on_key(int code, int value) {
    if (code < 0 || code > KEY_MAX) return;
    socket_send_key(code, value);
    int down = value != 0;
    if (passthrough) {
        if (value == 2) return;
        if (!down) {
            if (!pressed[code]) return;
            pressed[code] = 0;
            output_key(code, 0);
        } else {
            if (pressed[code]) return;
            pressed[code] = 1;
            output_key(code, 1);
        }
        return;
    }
    if (config.capture) {
        emit_event(EV_KEY, code, down ? 1 : 0);
        emit_sync();
        return;
    }

    if (!down) {
        if (!pressed[code]) return;
        pressed[code] = 0;
        TurboState *t = &turbos[code];
        if (t->active) {
            if (t->phase) output_key(t->target, 0);
            t->active = 0;
            source_target[code] = 0;
            return;
        }
        for (int i = 0; i < config.combo_count; i++) {
            ComboState *cs = &combo_state[i];
            if (!cs->active) continue;
            int participant = 0;
            for (int j = 0; j < config.combos[i].count; j++) {
                if (config.combos[i].keys[j] == code) participant = 1;
            }
            if (!participant) continue;
            int any_pressed = 0;
            for (int j = 0; j < config.combos[i].count; j++) {
                if (pressed[config.combos[i].keys[j]]) any_pressed = 1;
            }
            if (!any_pressed) cs->active = 0;
            return;
        }
        if (pending[code]) {
            pending[code] = 0;
            release_individual(code, 1);
            return;
        }
        release_individual(code, 0);
        return;
    }

    if (pressed[code]) return;
    pressed[code] = 1;

    for (int i = 0; i < config.combo_count; i++) {
        if (combo_state[i].active) continue;
        if (!combo_all_pressed(&config.combos[i])) continue;
        int conflict = 0;
        for (int j = 0; j < config.combos[i].count; j++) {
            int member = config.combos[i].keys[j];
            if (source_target[member] || turbos[member].active) conflict = 1;
        }
        if (conflict) continue;
        combo_activate(i);
        return;
    }

    if (config.combo_member[code]) {
        pending[code] = 1;
        pending_at[code] = now_ms + COMBO_WINDOW_MS;
        return;
    }

    apply_individual(code);
}

static void tick(void) {
    now_ms = monotonic_ms();
    for (int i = 0; i <= KEY_MAX; i++) {
        if (pending[i] && pressed[i] && now_ms >= pending_at[i]) {
            pending[i] = 0;
            apply_individual(i);
        }
        TurboState *t = &turbos[i];
        if (t->active && pressed[i] && now_ms >= t->next_ms) {
            if (t->phase) output_key(t->target, 0);
            else output_key(t->target, 1);
            t->phase = !t->phase;
            t->next_ms = now_ms + TURBO_INTERVAL_MS;
        }
    }
    for (int i = 0; i < config.combo_count; i++) {
        ComboState *cs = &combo_state[i];
        if (cs->active && now_ms >= cs->release_ms) {
            output_key(config.combos[i].target, 0);
            cs->active = 0;
        }
    }
}

static char *trim(char *s) {
    while (*s == ' ' || *s == '\t' || *s == '\r' || *s == '\n') s++;
    char *end = s + strlen(s);
    while (end > s && (end[-1] == ' ' || end[-1] == '\t'
                        || end[-1] == '\r' || end[-1] == '\n')) end--;
    *end = 0;
    return s;
}

static int parse_int(const char *s, int *out) {
    if (!s || !*s) return 0;
    char *end = NULL;
    long v = strtol(s, &end, 0);
    if (end == s || *trim(end) != 0) return 0;
    *out = (int)v;
    return 1;
}

static void reset_sticks(Config *c) {
    c->stick_enabled = 0;
    c->stick_deadzone[0] = 0;
    c->stick_deadzone[1] = 0;
    c->stick_curve[0] = 100;
    c->stick_curve[1] = 100;
    c->stick_floor[0] = 0;
    c->stick_floor[1] = 0;
    for (int i = 0; i < STICK_AXES; i++) {
        c->stick_center[i] = 0;
        c->stick_min[i] = -32767;
        c->stick_max[i] = 32767;
    }
    c->gate_enabled = 0;
    for (int s = 0; s < 2; s++) {
        for (int i = 0; i < GATE_POINTS; i++) c->gate_gain[s][i] = GATE_DEFAULT;
    }
}

static void reset_config(void) {
    memset(&config, 0, sizeof(config));
    config.enabled = 1;
    for (int i = 0; i <= KEY_MAX; i++) config.map[i] = -1;
    reset_sticks(&config);
}

static int load_config(void) {
    FILE *f = fopen(config_path, "r");
    if (!f) return 0;
    Config next;
    memset(&next, 0, sizeof(next));
    next.enabled = 1;
    for (int i = 0; i <= KEY_MAX; i++) next.map[i] = -1;
    reset_sticks(&next);

    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        char *s = trim(line);
        if (!*s || *s == '#') continue;
        char *eq = strchr(s, '=');
        if (!eq) continue;
        *eq = 0;
        char *key = trim(s);
        char *value = trim(eq + 1);
        if (!strcmp(key, "enabled")) {
            parse_int(value, &next.enabled);
        } else if (!strcmp(key, "capture")) {
            parse_int(value, &next.capture);
        } else if (!strcmp(key, "stick_enabled")) {
            parse_int(value, &next.stick_enabled);
        } else if (!strcmp(key, "stick_deadzone")) {
            int v;
            if (parse_int(value, &v)) {
                next.stick_deadzone[0] = v;
                next.stick_deadzone[1] = v;
            }
        } else if (!strcmp(key, "stick_deadzone_left")) {
            parse_int(value, &next.stick_deadzone[0]);
        } else if (!strcmp(key, "stick_deadzone_right")) {
            parse_int(value, &next.stick_deadzone[1]);
        } else if (!strcmp(key, "stick_curve")) {
            int v;
            if (parse_int(value, &v)) {
                next.stick_curve[0] = v;
                next.stick_curve[1] = v;
            }
        } else if (!strcmp(key, "stick_floor")) {
            int v;
            if (parse_int(value, &v)) {
                next.stick_floor[0] = v;
                next.stick_floor[1] = v;
            }
        } else if (!strcmp(key, "stick_floor_left")) {
            parse_int(value, &next.stick_floor[0]);
        } else if (!strcmp(key, "stick_floor_right")) {
            parse_int(value, &next.stick_floor[1]);
        } else if (!strcmp(key, "stick_curve_left")) {
            parse_int(value, &next.stick_curve[0]);
        } else if (!strcmp(key, "stick_curve_right")) {
            parse_int(value, &next.stick_curve[1]);
        } else if (!strcmp(key, "gate_enabled")) {
            parse_int(value, &next.gate_enabled);
        } else if (!strcmp(key, "gate_left") || !strcmp(key, "gate_right")) {
            int stick = key[5] == 'l' ? 0 : 1;
            char *save = NULL;
            char *token = strtok_r(value, ",", &save);
            int i = 0;
            while (token && i < GATE_POINTS) {
                int gain;
                if (parse_int(trim(token), &gain)) {
                    if (gain < 500) gain = 500;
                    if (gain > 2000) gain = 2000;
                    next.gate_gain[stick][i++] = gain;
                }
                token = strtok_r(NULL, ",", &save);
            }
        } else if (!strncmp(key, "axis_", 5)) {
            int idx = -1;
            if (!strcmp(key, "axis_x")) idx = 0;
            else if (!strcmp(key, "axis_y")) idx = 1;
            else if (!strcmp(key, "axis_z")) idx = 2;
            else if (!strcmp(key, "axis_rz")) idx = 3;
            else if (!strcmp(key, "axis_rx")) idx = 4;
            else if (!strcmp(key, "axis_ry")) idx = 5;
            if (idx < 0) continue;
            char *f1 = strchr(value, ':');
            if (!f1) continue;
            *f1 = 0;
            char *f2 = strchr(f1 + 1, ':');
            if (!f2) continue;
            *f2 = 0;
            int c, mn, mx;
            if (parse_int(trim(value), &c) && parse_int(trim(f1 + 1), &mn)
                    && parse_int(trim(f2 + 1), &mx) && mx > mn) {
                next.stick_center[idx] = c;
                next.stick_min[idx] = mn;
                next.stick_max[idx] = mx;
            }
        } else if (!strcmp(key, "map")) {
            char *colon = strchr(value, ':');
            if (!colon) continue;
            *colon = 0;
            int source, target;
            if (parse_int(trim(value), &source) && parse_int(trim(colon + 1), &target)
                    && source >= 0 && source <= KEY_MAX && target >= 0 && target <= KEY_MAX) {
                next.map[source] = target;
            }
        } else if (!strcmp(key, "turbo")) {
            int source;
            if (parse_int(value, &source) && source >= 0 && source <= KEY_MAX) {
                next.turbo[source] = 1;
            }
        } else if (!strcmp(key, "combo")) {
            if (next.combo_count >= MAX_COMBOS) continue;
            char *colon = strchr(value, ':');
            if (!colon) continue;
            *colon = 0;
            Combo *c = &next.combos[next.combo_count];
            char *save = NULL;
            char *token = strtok_r(value, ",", &save);
            while (token && c->count < MAX_COMBO_KEYS) {
                int source;
                if (parse_int(trim(token), &source) && source >= 0 && source <= KEY_MAX) {
                    int duplicate = 0;
                    for (int i = 0; i < c->count; i++) {
                        if (c->keys[i] == source) duplicate = 1;
                    }
                    if (!duplicate) c->keys[c->count++] = source;
                }
                token = strtok_r(NULL, ",", &save);
            }
            int target;
            if (c->count >= 2 && parse_int(trim(colon + 1), &target)
                    && target >= 0 && target <= KEY_MAX) {
                c->target = target;
                for (int i = 0; i < c->count; i++) next.combo_member[c->keys[i]] = 1;
                next.combo_count++;
            }
        }
    }
    fclose(f);
    release_all_targets();
    config = next;
    return 1;
}

static int config_changed(void) {
    struct stat st;
    if (stat(config_path, &st) != 0) return 0;
    long long mt = (long long)st.st_mtime * 1000LL + st.st_mtime_nsec / 1000000LL;
    long long size = (long long)st.st_size;
    if (mt == config_mtime && size == config_size) return 0;
    config_mtime = mt;
    config_size = size;
    return 1;
}

static void copy_capabilities(int src, int ui) {
    ioctl(ui, UI_SET_EVBIT, EV_SYN);
    ioctl(ui, UI_SET_EVBIT, EV_KEY);
    ioctl(ui, UI_SET_EVBIT, EV_ABS);

    uint8_t keybits[KEY_MAX / 8 + 2];
    memset(keybits, 0, sizeof(keybits));
    if (ioctl(src, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) >= 0) {
        for (int i = 0; i <= KEY_MAX; i++) {
            if (keybits[i / 8] & (1 << (i % 8))) ioctl(ui, UI_SET_KEYBIT, i);
        }
    }

    uint8_t absbits[ABS_MAX / 8 + 2];
    memset(absbits, 0, sizeof(absbits));
    if (ioctl(src, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) >= 0) {
        for (int i = 0; i <= ABS_MAX; i++) {
            if (!(absbits[i / 8] & (1 << (i % 8)))) continue;
            struct input_absinfo ai;
            if (ioctl(src, EVIOCGABS(i), &ai) < 0) continue;
            struct uinput_abs_setup setup;
            memset(&setup, 0, sizeof(setup));
            setup.code = (uint16_t)i;
            setup.absinfo = ai;
            ioctl(ui, UI_ABS_SETUP, &setup);
        }
    }
}

static int create_virtual(void) {
    ui_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ui_fd < 0) return 0;
    copy_capabilities(src_fd, ui_fd);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x2020;
    setup.id.product = 0x0111;
    setup.id.version = 0x0001;
    snprintf(setup.name, sizeof(setup.name), "Aurora Virtual Controller");
    if (ioctl(ui_fd, UI_DEV_SETUP, &setup) < 0
            || ioctl(ui_fd, UI_DEV_CREATE) < 0) {
        close(ui_fd);
        ui_fd = -1;
        return 0;
    }
    return 1;
}

static int find_default_device(char *out, size_t out_size) {
    FILE *f = popen("getevent -lp 2>/dev/null", "r");
    if (!f) return 0;
    char line[512];
    char current[256] = {0};
    int found = 0;
    while (fgets(line, sizeof(line), f)) {
        char *t = trim(line);
        if (!strncmp(t, "add device", 10)) {
            char *slash = strchr(t, '/');
            if (slash) snprintf(current, sizeof(current), "%s", slash);
        } else if (!strncmp(t, "name:", 5) && current[0]) {
            char *name = trim(t + 5);
            if (strcasestr(name, "odin controller")
                    || (strcasestr(name, "controller") && !strcasestr(name, "aurora"))) {
                snprintf(out, out_size, "%s", current);
                found = 1;
                break;
            }
        }
    }
    pclose(f);
    return found;
}

static int axis_index(int code) {
    switch (code) {
        case ABS_X: return 0;
        case ABS_Y: return 1;
        case ABS_Z: return 2;
        case ABS_RZ: return 3;
        case ABS_RX: return 4;
        case ABS_RY: return 5;
        default: return -1;
    }
}

static float axis_normalized(int idx, int value) {
    int c = config.stick_center[idx];
    int mn = config.stick_min[idx];
    int mx = config.stick_max[idx];
    if (mx <= c) mx = c + 1;
    if (mn >= c) mn = c - 1;
    float n = value >= c ? (value - c) / (float) (mx - c)
                         : (value - c) / (float) (c - mn);
    if (n > 1.0f) n = 1.0f;
    if (n < -1.0f) n = -1.0f;
    return n;
}

/*
 * Radial stick correction.
 *
 * Both axes of a stick are normalised against their own centre and travel,
 * the vector is clamped to a unit circle so diagonals cannot reach 1.41x, a
 * circular deadzone is removed, and the remaining magnitude is shaped by the
 * response curve. Direction is preserved: x and y are scaled together.
 */
/*
 * Per-angle gain measured by rolling the stick around its edge.  A gate that
 * is not a perfect circle reaches a different radius at each angle; dividing
 * by that radius makes every direction reach full scale at the physical stop.
 */
static float gate_gain(int stick, float x, float y) {
    if (!config.gate_enabled || stick < 0 || stick > 1) return 1.0f;
    if (x == 0.0f && y == 0.0f) return 1.0f;
    const float tau = 6.28318530718f;
    float pos = (atan2f(y, x) + 3.14159265359f) * (GATE_POINTS / tau);
    if (pos < 0.0f) pos = 0.0f;
    int i0 = (int) pos;
    if (i0 >= GATE_POINTS) i0 = GATE_POINTS - 1;
    int i1 = (i0 + 1) % GATE_POINTS;
    float f = pos - i0;
    float a = config.gate_gain[stick][i0];
    float b = config.gate_gain[stick][i1];
    return ((1.0f - f) * a + f * b) / 1000.0f;
}

static void learn_sample(int stick, float x, float y) {
    float r = sqrtf(x * x + y * y);
    if (r > 1.60f) return;
    if (r < 0.05f) {
        // Rest jitter: median of the last calm samples, so the settling tail
        // of a release cannot inflate the suggestion.
        calm_hist[calm_n % 32] = r;
        calm_n++;
        if (calm_n >= 15) {
            int n = calm_n < 32 ? calm_n : 32;
            float tmp[32];
            for (int i = 0; i < n; i++) tmp[i] = calm_hist[i];
            for (int i = 1; i < n; i++) {
                float v = tmp[i];
                int j = i - 1;
                while (j >= 0 && tmp[j] > v) {
                    tmp[j + 1] = tmp[j];
                    j--;
                }
                tmp[j + 1] = v;
            }
            learn_rest = tmp[n / 2];
        }
        return;
    }
    calm_n = 0;
    float angle = atan2f(y, x) + 3.14159265359f;
    int idx = (int) (angle * (GATE_POINTS / 6.28318530718f));
    if (idx < 0) idx = 0;
    if (idx >= GATE_POINTS) idx = 0;
    learn_samples++;
    if (r > learn_gate[stick][idx]) learn_gate[stick][idx] = r;
}

static void reset_learn(void) {
    for (int i = 0; i < STICK_AXES; i++) {
        learn_min[i] = 32767;
        learn_max[i] = -32767;
    }
    for (int st = 0; st < 2; st++) {
        for (int i = 0; i < GATE_POINTS; i++) learn_gate[st][i] = 0.0f;
    }
    learn_samples = 0;
    learn_reconnects = 0;
    learn_rest = 0.0f;
    calm_n = 0;
    if (learn_path[0]) unlink(learn_path);
}

static void write_learn(void) {
    if (!learn_path[0]) return;
    FILE *f = fopen(learn_path, "w");
    if (!f) return;
    fprintf(f, "samples=%lu\nreconnects=%d\nrest=%d\n",
            learn_samples, learn_reconnects, (int) lroundf(learn_rest * 1000.0f));
    for (int i = 0; i < 4; i++) {
        fprintf(f, "axis_%d=%d:%d\n", i, learn_min[i], learn_max[i]);
    }
    for (int st = 0; st < 2; st++) {
        fprintf(f, st == 0 ? "gate_left=" : "gate_right=");
        for (int i = 0; i < GATE_POINTS; i++) {
            if (i > 0) fputc(',', f);
            fprintf(f, "%d", (int) lroundf(learn_gate[st][i] * 1000.0f));
        }
        fputc('\n', f);
    }
    fclose(f);
    chmod(learn_path, 0644);
}

static void stick_output(int pair, float *out_x, float *out_y) {
    float nx = axis_normalized(pair, raw_axis[pair]);
    float ny = axis_normalized(pair + 1, raw_axis[pair + 1]);
    learn_sample(pair >> 1, nx, ny);
    float gain = gate_gain(pair >> 1, nx, ny);
    nx *= gain;
    ny *= gain;
    float r = sqrtf(nx * nx + ny * ny);
    if (r > 1.0f) {
        nx /= r;
        ny /= r;
        r = 1.0f;
    }
    int stick = pair >> 1;
    float dz = (stick <= 1 ? config.stick_deadzone[stick] : 0) / 1000.0f;
    if (dz < 0.0f) dz = 0.0f;
    if (dz > 0.80f) dz = 0.80f;
    // Hysteresis: leave the deadzone at dz, but only settle back below 75% of
    // it. Without this, a thumb resting near the edge makes the floor jump
    // on and off, which reads as a twitch.
    if (r > dz) stick_active[stick] = 1;
    else if (r < dz * 0.75f) stick_active[stick] = 0;
    int active = stick_active[stick];
    float m = active ? (r - dz) / (1.0f - dz) : 0.0f;
    if (m < 0.0f) m = 0.0f;
    float gamma = (stick <= 1 ? config.stick_curve[stick] : 100) / 100.0f;
    if (gamma < 0.20f) gamma = 0.20f;
    if (gamma > 4.0f) gamma = 4.0f;
    if (m > 0.0f) {
        /* A pure power curve has zero slope at the deadzone edge, so a high
         * setting makes gentle pushes vanish into the game's own deadzone.
         * Blend in a small linear share that fades out towards full tilt:
         * the start stays slow but alive, the top keeps the precision shape. */
        float base = m;
        float shaped = powf(base, gamma);
        float blend = 0.25f * (gamma > 1.0f ? gamma - 1.0f : 0.0f);
        if (blend > 0.0f) {
            float w = blend * (1.0f - base);
            m = w * base + (1.0f - w) * shaped;
        } else {
            m = shaped;
        }
    }
    /* Anti-deadzone: land just above the game's own threshold so a gentle
     * push registers as the slowest speed instead of nothing. */
    float floorv = (stick <= 1 ? config.stick_floor[stick] : 0) / 1000.0f;
    if (active && floorv > 0.0f) {
        if (floorv > 0.90f) floorv = 0.90f;
        /* Ramp the floor in over the first 15% of usable travel instead of
         * stepping to it at the deadzone edge. A step made tiny pushes jump
         * to the floor and made the response speed unpredictable. */
        float ramp = m / 0.15f;
        if (ramp > 1.0f) ramp = 1.0f;
        m = (1.0f - floorv) * m + floorv * ramp;
    }
    if (r > 0.0f) {
        *out_x = nx / r * m;
        *out_y = ny / r * m;
    } else {
        *out_x = 0.0f;
        *out_y = 0.0f;
    }
}

static int unit_to_axis(float v) {
    int out = (int) lroundf(v * 32767.0f);
    if (out > 32767) out = 32767;
    if (out < -32767) out = -32767;
    return out;
}

static int axis_code(int idx) {
    switch (idx) {
        case 0: return ABS_X;
        case 1: return ABS_Y;
        case 2: return ABS_Z;
        case 3: return ABS_RZ;
        case 4: return ABS_RX;
        case 5: return ABS_RY;
        default: return -1;
    }
}

/* Opens the physical controller and records the identity of the node we hold. */
static int open_source(void) {
    int fd = open(device, O_RDWR | O_CLOEXEC);
    if (fd < 0) return 0;
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        close(fd);
        return 0;
    }
    struct stat st;
    if (fstat(fd, &st) == 0) {
        src_dev_id = st.st_dev;
        src_ino = st.st_ino;
    }
    src_fd = fd;
    grabbed = 1;
    return 1;
}

/* The controller node is recreated when it sleeps, reconnects or the game
 * switches USB modes. The old fd then points at a deleted node and silently
 * stops receiving input, so compare the live node with the one we hold. */
static int source_alive(void) {
    if (src_fd < 0) return 0;
    struct stat st;
    if (stat(device, &st) != 0) return 0;
    return st.st_dev == src_dev_id && st.st_ino == src_ino;
}

static int reconnect_source(void) {
    if (src_fd >= 0) {
        ioctl(src_fd, EVIOCGRAB, 0);
        close(src_fd);
        src_fd = -1;
    }
    grabbed = 0;
    release_all_targets();
    char found[256];
    if (find_default_device(found, sizeof(found)) && strcmp(found, device)) {
        snprintf(device, sizeof(device), "%s", found);
    }
    if (open_source()) {
        learn_reconnects++;
        fprintf(stderr, "reconnected to %s\n", device);
        fflush(stderr);
        return 1;
    }
    return 0;
}

static void derive_side_path(char *out, size_t size, const char *name) {
    out[0] = 0;
    const char *slash = strrchr(config_path, '/');
    if (!slash) return;
    int dir = (int)(slash - config_path);
    if (dir <= 0 || (size_t)dir + strlen(name) + 2 >= size) return;
    snprintf(out, size, "%.*s/%s", dir, config_path, name);
}

static void derive_park_path(void) {
    derive_side_path(park_path, sizeof(park_path), "controller-passthrough");
    derive_side_path(learn_path, sizeof(learn_path), "controller-learn.conf");
    derive_side_path(learn_reset_path, sizeof(learn_reset_path), "controller-learn-reset");
    derive_side_path(trace_path, sizeof(trace_path), "controller-trace");
}

/* Optional raw-vs-corrected logging, enabled by creating a marker file. */
static void trace_tick(void) {
    int wanted = trace_path[0] && access(trace_path, F_OK) == 0;
    if (wanted && !trace_file) {
        trace_file = fopen(trace_path, "a");
        if (trace_file) {
            setvbuf(trace_file, NULL, _IOLBF, 0);
            fprintf(trace_file, "# cal dzL=%d dzR=%d curveL=%d curveR=%d floorL=%d floorR=%d gate=%d",
                    config.stick_deadzone[0], config.stick_deadzone[1],
                    config.stick_curve[0], config.stick_curve[1],
                    config.stick_floor[0], config.stick_floor[1],
                    config.gate_enabled);
            for (int i = 0; i < 4; i++) {
                fprintf(trace_file, " a%d=%d:%d:%d", i, config.stick_center[i],
                        config.stick_min[i], config.stick_max[i]);
            }
            fprintf(trace_file, "\n");
        }
    } else if (!wanted && trace_file) {
        fclose(trace_file);
        trace_file = NULL;
    }
}

/* The Joystick app parks the remapper so it can read the physical axes
 * directly.  Parking releases EVIOCGRAB and discards input until the marker
 * disappears, then re-grabs and resumes normal remapping. */
static void update_grab(void) {
    int park = park_path[0] && access(park_path, F_OK) == 0;
    if (park && grabbed) {
        ioctl(src_fd, EVIOCGRAB, 0);
        grabbed = 0;
        release_all_targets();
    } else if (!park && !grabbed) {
        if (ioctl(src_fd, EVIOCGRAB, 1) >= 0) grabbed = 1;
    }
}

static void usage(const char *name) {
    fprintf(stderr, "usage: %s [--device PATH] --config PATH [--pid PATH] [--socket NAME]\n", name);
}

int main(int argc, char **argv) {
    device[0] = 0;
    config_path[0] = 0;
    pid_path[0] = 0;
    socket_name[0] = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--device") && i + 1 < argc) {
            snprintf(device, sizeof(device), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--config") && i + 1 < argc) {
            snprintf(config_path, sizeof(config_path), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--pid") && i + 1 < argc) {
            snprintf(pid_path, sizeof(pid_path), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--socket") && i + 1 < argc) {
            snprintf(socket_name, sizeof(socket_name), "%s", argv[++i]);
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (!config_path[0]) {
        usage(argv[0]);
        return 2;
    }
    derive_park_path();
    for (int i = 0; i < STICK_AXES; i++) {
        learn_min[i] = 32767;
        learn_max[i] = -32767;
    }
    if (!device[0] && !find_default_device(device, sizeof(device))) {
        fprintf(stderr, "controller not found\n");
        return 1;
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    if (!open_source()) {
        fprintf(stderr, "open %s: %s\n", device, strerror(errno));
        return 1;
    }
    if (!create_virtual()) {
        fprintf(stderr, "uinput create failed: %s\n", strerror(errno));
        ioctl(src_fd, EVIOCGRAB, 0);
        close(src_fd);
        return 1;
    }
    reset_config();
    load_config();
    config_changed();
    socket_enabled = socket_name[0] && open_socket_server();
    if (!config.enabled && !socket_enabled) {
        ioctl(ui_fd, UI_DEV_DESTROY);
        close(ui_fd);
        ioctl(src_fd, EVIOCGRAB, 0);
        close(src_fd);
        return 0;
    }
    passthrough = !config.enabled;
    write_pid();
    now_ms = monotonic_ms();
    next_source_check = now_ms + 1000;
    next_learn_write = now_ms + 5000;
    long long next_config_check = now_ms;

    while (!stop_requested) {
        now_ms = monotonic_ms();
        if (now_ms >= next_config_check) {
            trace_tick();
            if (config_changed()) {
                if (!load_config()) break;
                if (!config.enabled && !socket_enabled) break;
                passthrough = !config.enabled;
            }
            next_config_check = now_ms + CONFIG_POLL_MS;
        }
        update_grab();
        if (learn_reset_path[0] && access(learn_reset_path, F_OK) == 0) {
            unlink(learn_reset_path);
            reset_learn();
        }
        if (config.stick_enabled && now_ms >= next_learn_write) {
            next_learn_write = now_ms + 5000;
            write_learn();
        }
        if (now_ms >= next_source_check) {
            next_source_check = now_ms + 1000;
            if (!source_alive() && reconnect_source()) {
                update_grab();
            }
        }
        tick();
        int timeout = 10;
        for (int i = 0; i <= KEY_MAX; i++) {
            if (turbos[i].active) {
                long long delta = turbos[i].next_ms - now_ms;
                if (delta < timeout) timeout = (int)delta;
            }
            if (pending[i] && pressed[i]) {
                long long delta = pending_at[i] - now_ms;
                if (delta < timeout) timeout = (int)delta;
            }
        }
        for (int i = 0; i < config.combo_count; i++) {
            if (combo_state[i].active) {
                long long delta = combo_state[i].release_ms - now_ms;
                if (delta < timeout) timeout = (int)delta;
            }
        }
        if (timeout < 0) timeout = 0;

        struct pollfd pfds[2];
        pfds[0].fd = src_fd;
        pfds[0].events = POLLIN;
        pfds[0].revents = 0;
        int count = 1;
        if (client_fd >= 0) {
            pfds[count].fd = client_fd;
            pfds[count].events = POLLIN;
            pfds[count].revents = 0;
            count++;
        } else if (server_fd >= 0) {
            pfds[count].fd = server_fd;
            pfds[count].events = POLLIN;
            pfds[count].revents = 0;
            count++;
        }

        int pr = poll(pfds, (nfds_t)count, timeout);
        if (pr < 0 && errno != EINTR) break;
        if (pr <= 0) continue;

        if (count > 1 && pfds[1].fd == server_fd && (pfds[1].revents & POLLIN)) {
            accept_client();
        } else if (count > 1 && pfds[1].fd == client_fd) {
            if (pfds[1].revents & (POLLIN | POLLHUP | POLLERR)) read_commands();
        }

        if (!(pfds[0].revents & POLLIN)) continue;
        if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            if (reconnect_source()) update_grab();
            continue;
        }
        struct input_event ev[64];
        ssize_t n = read(src_fd, ev, sizeof(ev));
        if (n < 0 && errno == ENODEV) {
            if (reconnect_source()) update_grab();
            continue;
        }
        if (n <= 0) continue;
        if (!grabbed) continue;
        for (size_t i = 0; i < (size_t)n / sizeof(ev[0]); i++) {
            switch (ev[i].type) {
                case EV_KEY:
                    on_key(ev[i].code, ev[i].value);
                    break;
                case EV_ABS: {
                    int idx = config.stick_enabled ? axis_index(ev[i].code) : -1;
                    if (idx < 0) {
                        socket_send_axis(ev[i].code, ev[i].value);
                        if (!swallow_all) {
                            emit_event(ev[i].type, ev[i].code, ev[i].value);
                        }
                        break;
                    }
                    raw_axis[idx] = ev[i].value;
                    if (ev[i].value < learn_min[idx]) learn_min[idx] = ev[i].value;
                    if (ev[i].value > learn_max[idx]) learn_max[idx] = ev[i].value;
                    int pair = idx & ~1;
                    float ox, oy;
                    stick_output(pair, &ox, &oy);
                    int out[2] = { unit_to_axis(ox), unit_to_axis(oy) };
                    if (trace_file && pair <= 2) {
                        fprintf(trace_file, "%lld,%d,%d,%d,%d,%d\n", now_ms,
                                pair >> 1, raw_axis[pair], raw_axis[pair + 1],
                                out[0], out[1]);
                    }
                    for (int k = 0; k < 2; k++) {
                        int slot = pair + k;
                        int code = axis_code(slot);
                        if (code < 0) continue;
                        if (out[k] == last_axis[slot] && slot != idx) continue;
                        last_axis[slot] = out[k];
                        socket_send_axis(code, out[k]);
                        if (!swallow_all) emit_event(EV_ABS, code, out[k]);
                    }
                    break;
                }
                case EV_SYN:
                case EV_MSC:
                    if (!swallow_all) emit_event(ev[i].type, ev[i].code, ev[i].value);
                    break;
                default:
                    break;
            }
        }
    }

    release_all_targets();
    close_client();
    if (server_fd >= 0) {
        close(server_fd);
        server_fd = -1;
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
