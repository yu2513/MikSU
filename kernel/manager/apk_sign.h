#ifndef __KSU_H_APK_V2_SIGN
#define __KSU_H_APK_V2_SIGN

#include <linux/types.h>

bool is_manager_apk(char *path);
bool ksu_apk_matches_certificate(char *path, unsigned size, const char *sha256);
int get_pkg_from_apk_dir_path(char *pkg, const char *path);

#endif
