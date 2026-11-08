#include "ksu.h"
#include "linux/cred.h"
#include "linux/namei.h"
#include "util.h"
#include <linux/err.h>
#include <linux/fs.h>
#include <linux/list.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/types.h>
#include <linux/version.h>

#include "policy/allowlist.h"
#include "manager/apk_sign.h"
#include "klog.h" // IWYU pragma: keep
#include "manager/manager_identity.h"
#include "manager/throne_tracker.h"
#include "uapi/supercall.h"

static DEFINE_MUTEX(throne_tracker_mutex);

uid_t ksu_manager_appid = KSU_INVALID_APPID;
uid_t ksu_hidden_manager_appid = KSU_INVALID_APPID;

#define DATA_PATH_LEN 384 // 384 is enough for /data/app/<package>/base.apk

static char cached_manager_apk_path[DATA_PATH_LEN];

#define SYSTEM_PACKAGES_LIST_PATH "/data/system/packages.list"

struct uid_data {
    struct list_head list;
    u32 uid;
    char package[KSU_MAX_PACKAGE_NAME];
};

static uid_t manager_uid(const char *package, struct list_head *uid_data)
{
    struct list_head *list = (struct list_head *)uid_data;
    struct uid_data *np;

    list_for_each_entry (np, list, list) {
        if (!strcmp(np->package, package))
            return np->uid;
    }
    return KSU_INVALID_APPID;
}

struct data_path {
    char dirpath[DATA_PATH_LEN];
    int depth;
    struct list_head list;
};

struct my_dir_context {
    struct dir_context ctx;
    struct list_head *data_path_list;
    char *parent_dir;
    void *private_data;
    int depth;
    const char *package;
    bool hidden;
};
// https://docs.kernel.org/filesystems/porting.html
// filldir_t (readdir callbacks) calling conventions have changed. Instead of returning 0 or -E... it returns bool now. false means "no more" (as -E... used to) and true - "keep going" (as 0 in old calling conventions). Rationale: callers never looked at specific -E... values anyway. -> iterate_shared() instances require no changes at all, all filldir_t ones in the tree converted.
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 1, 0)
#define FILLDIR_RETURN_TYPE bool
#define FILLDIR_ACTOR_CONTINUE true
#define FILLDIR_ACTOR_STOP false
#else
#define FILLDIR_RETURN_TYPE int
#define FILLDIR_ACTOR_CONTINUE 0
#define FILLDIR_ACTOR_STOP -EINVAL
#endif

static bool maybe_manager_apk_dir(const char *path, const char *package)
{
    _Static_assert(sizeof(KSU_PACKAGE_NAME) < KSU_MAX_PACKAGE_NAME, "KSU_PACKAGE_NAME too long!");
    char pkg[KSU_MAX_PACKAGE_NAME];
    if (get_pkg_from_apk_dir_path(pkg, path) < 0) {
        pr_err("Failed to get package name from apk dir path: %s\n", path);
        return false;
    }

    // pkg is `<real package>`
    return !strcmp(pkg, package);
}

static FILLDIR_RETURN_TYPE my_actor(struct dir_context *ctx, const char *name, int namelen, loff_t off, u64 ino,
                                    unsigned int d_type)
{
    struct my_dir_context *my_ctx = container_of(ctx, struct my_dir_context, ctx);

    // we put the apk path we collected here
    char *candidate_path = (char *)my_ctx->private_data;

    char dirpath[DATA_PATH_LEN];

    if (!strncmp(name, "..", namelen) || !strncmp(name, ".", namelen))
        return FILLDIR_ACTOR_CONTINUE; // Skip "." and ".."

    if (d_type == DT_DIR && namelen >= 8 && !strncmp(name, "vmdl", 4) && !strncmp(name + namelen - 4, ".tmp", 4)) {
        pr_info("Skipping directory: %.*s\n", namelen, name);
        return FILLDIR_ACTOR_CONTINUE; // Skip staging package
    }

    if (snprintf(dirpath, DATA_PATH_LEN, "%s/%.*s", my_ctx->parent_dir, namelen, name) >= DATA_PATH_LEN) {
        pr_err("Path too long: %s/%.*s\n", my_ctx->parent_dir, namelen, name);
        return FILLDIR_ACTOR_CONTINUE;
    }

    if (d_type == DT_DIR && my_ctx->depth > 0) {
        struct data_path *data = kzalloc(sizeof(struct data_path), GFP_KERNEL);

        if (!data) {
            pr_err("Failed to allocate memory for %s\n", dirpath);
            return FILLDIR_ACTOR_CONTINUE;
        }

        strscpy(data->dirpath, dirpath, DATA_PATH_LEN);
        data->depth = my_ctx->depth - 1;
        list_add_tail(&data->list, my_ctx->data_path_list);

        return FILLDIR_ACTOR_CONTINUE;
    }

    // now put this on candidate_path
    if (d_type == DT_REG && namelen == 8 && !memcmp(name, "base.apk", 8)) {
        if (maybe_manager_apk_dir(my_ctx->parent_dir, my_ctx->package)) {
            snprintf(candidate_path, DATA_PATH_LEN, "%s/%.*s", my_ctx->parent_dir, namelen, name);
        }
        // As soon as we met the base.apk, we can stop
        return FILLDIR_ACTOR_STOP;
    }

    return FILLDIR_ACTOR_CONTINUE;
}

static uid_t search_manager(const char *path, int depth, struct list_head *uid_data,
                            const struct ksu_manager_identity_record *identity)
{
    uid_t result = KSU_INVALID_APPID;
    const char *package = identity ? identity->package : KSU_PACKAGE_NAME;
    int i, stop = 0;
    struct list_head data_path_list;
    INIT_LIST_HEAD(&data_path_list);
    unsigned long data_app_magic = 0;

    // First depth
    struct data_path data;
    strscpy(data.dirpath, path, DATA_PATH_LEN);
    data.depth = depth;
    list_add_tail(&data.list, &data_path_list);

    // we put the apk path we collected here
    char candidate_path[DATA_PATH_LEN];
    candidate_path[0] = 0;

    for (i = depth; i >= 0; i--) {
        struct data_path *pos, *n;

        list_for_each_entry_safe (pos, n, &data_path_list, list) {
            if (stop)
                goto skip_iterate;
            struct my_dir_context ctx = { .ctx.actor = my_actor,
                                          .data_path_list = &data_path_list,
                                          .parent_dir = pos->dirpath,
                                          .private_data = candidate_path,
                                          .package = package,
                                          .hidden = identity != NULL,
                                          .depth = pos->depth };
            struct file *file = ksu_filp_open_nonotify(pos->dirpath, O_RDONLY | O_NOFOLLOW | O_NOATIME | O_DIRECTORY);
            if (IS_ERR(file)) {
                pr_err("Failed to open directory: %s, err: %ld\n", pos->dirpath, PTR_ERR(file));
                goto skip_iterate;
            }

            // grab magic on first folder, which is /data/app
            if (!data_app_magic) {
                if (file->f_inode->i_sb->s_magic) {
                    data_app_magic = file->f_inode->i_sb->s_magic;
                    pr_info("%s: dir: %s got magic! 0x%lx\n", __func__, pos->dirpath, data_app_magic);
                } else {
                    filp_close(file, NULL);
                    goto skip_iterate;
                }
            }

            if (file->f_inode->i_sb->s_magic != data_app_magic) {
                pr_info("%s: skip: %s magic: 0x%lx expected: 0x%lx\n", __func__, pos->dirpath,
                        file->f_inode->i_sb->s_magic, data_app_magic);
                filp_close(file, NULL);
                goto skip_iterate;
            }

            iterate_dir(file, &ctx.ctx);
            filp_close(file, NULL);

            if (!candidate_path[0])
                goto skip_iterate;

            bool is_manager = identity ? ksu_apk_matches_certificate(candidate_path,
                identity->certificate_size, identity->certificate_sha256) : is_manager_apk(candidate_path);
            pr_info("Found manager base.apk at path: %s, is_manager: %d\n", candidate_path, is_manager);

            // Only crown if signature verification pass
            if (unlikely(!is_manager)) {
                candidate_path[0] = 0;
                goto skip_iterate;
            }

            stop = 1;
            result = manager_uid(package, uid_data);
            if (!identity && result != KSU_INVALID_APPID)
                strscpy(cached_manager_apk_path, candidate_path, sizeof(cached_manager_apk_path));

        skip_iterate:
            list_del(&pos->list);
            if (pos != &data)
                kfree(pos);
        }
    }
    return result;
}

static bool read_hidden_identity(const char *package, struct ksu_manager_identity_record *record)
{
    struct ksu_manager_identity_record identity;
    char path[DATA_PATH_LEN];
    loff_t offset = 0;
    int i;
    bool valid = false;
    if (snprintf(path, sizeof(path), "/data/user_de/0/%s/files/.manager-identity", package) >= sizeof(path))
        return false;
    struct file *file = ksu_filp_open_nonotify(path, O_RDONLY | O_NOFOLLOW | O_NOATIME | O_NONBLOCK);
    if (IS_ERR(file))
        goto out;
    struct inode *inode = file_inode(file);
    if (!S_ISREG(inode->i_mode) || inode->i_uid.val != 0 || inode->i_gid.val != 0 ||
        (inode->i_mode & 07777) != 0600 || inode->i_nlink != 1 ||
        i_size_read(inode) != sizeof(identity))
        goto close;
    if (kernel_read(file, &identity, sizeof(identity), &offset) != sizeof(identity) ||
        identity.magic != KSU_MANAGER_IDENTITY_MAGIC || identity.version != KSU_MANAGER_IDENTITY_VERSION ||
        identity.certificate_size < 1 || identity.certificate_size > 1024 ||
        !identity.package[0] || identity.package[255] || identity.certificate_sha256[64])
        goto close;
    bool dot = false;
    for (i = 0; identity.package[i]; i++) {
        char c = identity.package[i];
        if (c == '.') {
            if (i == 0 || identity.package[i - 1] == '.' || !identity.package[i + 1])
                goto close;
            dot = true;
        } else if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
            goto close;
        } else if ((i == 0 || identity.package[i - 1] == '.') && !(c >= 'a' && c <= 'z')) {
            goto close;
        }
    }
    if (!dot || strcmp(identity.package, package) || !strcmp(identity.package, KSU_PACKAGE_NAME))
        goto close;
    for (i = 0; i < 64; i++) {
        char c = identity.certificate_sha256[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            goto close;
    }
    valid = true;
    *record = identity;
close:
    filp_close(file, NULL);
out:
    return valid;
}

static void refresh_hidden_manager(struct list_head *uid_data)
{
    struct uid_data *entry;
    struct ksu_manager_identity_record identity;
    uid_t appid = KSU_INVALID_APPID;
    list_for_each_entry(entry, uid_data, list) {
        if (entry->uid >= 10000 && entry->uid < KSU_PER_USER_RANGE &&
            read_hidden_identity(entry->package, &identity)) {
            appid = search_manager("/data/app", 2, uid_data, &identity);
            if (appid != KSU_INVALID_APPID)
                break;
        }
    }
    WRITE_ONCE(ksu_hidden_manager_appid, appid);
}

static bool is_uid_exist(uid_t uid, char *package, void *data)
{
    struct list_head *list = (struct list_head *)data;
    struct uid_data *np;

    bool exist = false;
    list_for_each_entry (np, list, list) {
        if (np->uid == uid % PER_USER_RANGE && strncmp(np->package, package, KSU_MAX_PACKAGE_NAME) == 0) {
            exist = true;
            break;
        }
    }
    return exist;
}

int track_throne(bool prune_only)
{
    int result = 0;
    mutex_lock(&throne_tracker_mutex);
    const struct cred *old_cred = override_creds(ksu_cred);
    struct file *fp = filp_open(SYSTEM_PACKAGES_LIST_PATH, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("%s: open " SYSTEM_PACKAGES_LIST_PATH " failed: %ld\n", __func__, PTR_ERR(fp));
        result = PTR_ERR(fp);
        goto out_revert_cred;
    }

    struct list_head uid_list;
    INIT_LIST_HEAD(&uid_list);

    char chr = 0;
    loff_t pos = 0;
    loff_t line_start = 0;
    char buf[KSU_MAX_PACKAGE_NAME];
    for (;;) {
        ssize_t count = kernel_read(fp, &chr, sizeof(chr), &pos);
        if (count != sizeof(chr)) {
            if (count < 0)
                result = count;
            break;
        }
        if (chr != '\n')
            continue;

        count = kernel_read(fp, buf, sizeof(buf) - 1, &line_start);
        if (count <= 0) {
            result = count < 0 ? count : -EIO;
            break;
        }
        buf[count] = '\0';

        struct uid_data *data = kzalloc(sizeof(struct uid_data), GFP_KERNEL);
        if (!data) {
            filp_close(fp, 0);
            result = -ENOMEM;
            goto out;
        }

        char *tmp = buf;
        const char *delim = " ";
        char *package = strsep(&tmp, delim);
        char *uid = strsep(&tmp, delim);
        if (!uid || !package) {
            kfree(data);
            pr_err("update_uid: package or uid is NULL!\n");
            result = -EINVAL;
            break;
        }

        u32 res;
        if (kstrtou32(uid, 10, &res)) {
            kfree(data);
            pr_err("update_uid: uid parse err\n");
            result = -EINVAL;
            break;
        }
        data->uid = res;
        strscpy(data->package, package, sizeof(data->package));
        list_add_tail(&data->list, &uid_list);
        // reset line start
        line_start = pos;
    }
    filp_close(fp, 0);
    if (result)
        goto out;

    // now update uid list
    struct uid_data *np;
    struct uid_data *n;

    if (prune_only)
        goto prune;

    refresh_hidden_manager(&uid_list);
    // first, check if manager_uid exist!
    uid_t manager_package_uid = KSU_INVALID_APPID;
    bool need_rescan = false;
    list_for_each_entry (np, &uid_list, list) {
        if (strcmp(np->package, KSU_PACKAGE_NAME) == 0) {
            manager_package_uid = np->uid;
            break;
        }
    }

    if (manager_package_uid == KSU_INVALID_APPID) {
        cached_manager_apk_path[0] = 0;
        if (READ_ONCE(ksu_manager_appid) != KSU_INVALID_APPID) {
            pr_info("manager is uninstalled, invalidate it!\n");
            ksu_invalidate_manager_uid();
            goto prune;
        }
    } else {
        if (READ_ONCE(ksu_manager_appid) == KSU_INVALID_APPID) {
            need_rescan = true;
        } else if (unlikely(np->uid != READ_ONCE(ksu_manager_appid))) {
            // this should not happen in normal android system
            pr_info("manager uid changed, invalidate it!\n");
            cached_manager_apk_path[0] = 0;
            ksu_invalidate_manager_uid();
            need_rescan = true;
        } else {
            // uid unchanged, skip
            pr_info("manager uid unchanged, skip search!\n");
        }
    }

    if (need_rescan) {
        // If the apk path still exists, the verification result won't be changed
        if (cached_manager_apk_path[0] && READ_ONCE(ksu_manager_appid) != KSU_INVALID_APPID) {
            struct path p;
            int ret = kern_path(cached_manager_apk_path, 0, &p);
            if (ret == 0) {
                // skip search
                pr_info("manager apk path unchanged, skip search!\n");
                path_put(&p);
                goto prune;
            } else if (ret != -ENOENT) {
                pr_err("stat cached manager path failed: %d (path=%s)\n", ret, cached_manager_apk_path);
            }
        }
        pr_info("Searching manager " KSU_PACKAGE_NAME "...\n");
        ksu_set_manager_appid(search_manager("/data/app", 2, &uid_list, NULL));
        pr_info("Search manager finished\n");
    }

prune:
    // then prune the allowlist
    ksu_prune_allowlist(is_uid_exist, &uid_list);
out:
    // free uid_list
    list_for_each_entry_safe (np, n, &uid_list, list) {
        list_del(&np->list);
        kfree(np);
    }
out_revert_cred:
    revert_creds(old_cred);
    mutex_unlock(&throne_tracker_mutex);
    return result;
}

void __init ksu_throne_tracker_init()
{
    // nothing to do
}

void ksu_throne_tracker_exit(void)
{
    // nothing to do
}
