#define _GNU_SOURCE
#include <errno.h>
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

typedef struct {
    int enabled;
    int capture;
    int map[KEY_MAX + 1];
    int turbo[KEY_MAX + 1];
    int combo_member[KEY_MAX + 1];
    Combo combos[MAX_COMBOS];
    int combo_count;
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
    if (code == ABS_X || code == ABS_Y || code == ABS_HAT0X || code == ABS_HAT0Y) {
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

static void reset_config(void) {
    memset(&config, 0, sizeof(config));
    config.enabled = 1;
    for (int i = 0; i <= KEY_MAX; i++) config.map[i] = -1;
}

static int load_config(void) {
    FILE *f = fopen(config_path, "r");
    if (!f) return 0;
    Config next;
    memset(&next, 0, sizeof(next));
    next.enabled = 1;
    for (int i = 0; i <= KEY_MAX; i++) next.map[i] = -1;

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

static void usage(const char *name) {
    fprintf(stderr, "usage: %s [--device PATH] --config PATH [--pid PATH] [--socket NAME]\n", name);
}

int main(int argc, char **argv) {
    char device[256] = {0};
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
    if (!device[0] && !find_default_device(device, sizeof(device))) {
        fprintf(stderr, "controller not found\n");
        return 1;
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    src_fd = open(device, O_RDWR | O_CLOEXEC);
    if (src_fd < 0) {
        fprintf(stderr, "open %s: %s\n", device, strerror(errno));
        return 1;
    }
    if (ioctl(src_fd, EVIOCGRAB, 1) < 0) {
        fprintf(stderr, "grab %s: %s\n", device, strerror(errno));
        close(src_fd);
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
    long long next_config_check = now_ms;

    while (!stop_requested) {
        now_ms = monotonic_ms();
        if (now_ms >= next_config_check) {
            if (config_changed()) {
                if (!load_config()) break;
                if (!config.enabled && !socket_enabled) break;
                passthrough = !config.enabled;
            }
            next_config_check = now_ms + CONFIG_POLL_MS;
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
        struct input_event ev[64];
        ssize_t n = read(src_fd, ev, sizeof(ev));
        if (n <= 0) continue;
        for (size_t i = 0; i < (size_t)n / sizeof(ev[0]); i++) {
            switch (ev[i].type) {
                case EV_KEY:
                    on_key(ev[i].code, ev[i].value);
                    break;
                case EV_ABS:
                    socket_send_axis(ev[i].code, ev[i].value);
                    if (!swallow_all) emit_event(ev[i].type, ev[i].code, ev[i].value);
                    break;
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
