#include <assert.h>
#include <stdbool.h>
#include <string.h>

#define __read_mostly
#define pr_info(...) ((void)0)
#define pr_err(...) ((void)0)
#define EINVAL 22

struct filesystem_type { const char *name; };
struct super_block { struct filesystem_type *s_type; };
struct inode { struct super_block *i_sb; };
struct dentry { struct inode *d_inode; };
struct path { struct dentry *dentry; };

static bool ready;
static int scans;
static int observers;
static int allowlist_loads;
static bool milksu_root_is_ready(void) { return ready; }
static void ksu_load_allow_list(void) { ++allowlist_loads; }
static void ksu_observer_init(void) { ++observers; }
static int track_throne(bool prune_only)
{
    assert(!prune_only);
    assert(observers == 1);
    ++scans;
    return 0;
}
static void ksu_stop_input_hook_runtime(void) {}
static void ksu_selinux_hide_handle_post_fs_data(void) {}
static void ksu_feature_apply_forced(void) {}
static void ksu_avc_spoof_late_init(void) {}
static void ksu_selinux_hide_drop_backup_if_unused(void) {}
static int kern_path(const char *name, int flags, struct path *path) { return -1; }
static void path_put(struct path *path) {}
void ext4_unregister_sysfs(struct super_block *sb) {}

#include "../../kernel/runtime/boot_event.c"

int main(void)
{
    on_post_fs_data();
    assert(scans == 0 && allowlist_loads == 0);
    ready = true;
    on_post_fs_data();
    assert(scans == 1 && allowlist_loads == 1);
    on_post_fs_data();
    assert(scans == 1 && allowlist_loads == 1);
    // No package installation or packages.list notification has occurred.
    on_boot_completed();
    assert(scans == 2 && ksu_boot_completed);
    return 0;
}
