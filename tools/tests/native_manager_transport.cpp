#include <algorithm>
#include <cassert>
#include <cstdarg>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

#include "../../manager/app/src/main/cpp/ksu.cc"

static int current_driver = -1;
static bool permitted = true;
static unsigned current_flags;

extern "C" ssize_t __real_readlink(const char *, char *, size_t);
extern "C" ssize_t __wrap_readlink(const char *path, char *buffer, size_t capacity) {
    char expected[64];
    snprintf(expected, sizeof(expected), "/proc/self/fd/%d", current_driver);
    if (current_driver >= 0 && strcmp(path, expected) == 0) {
        constexpr auto name = "anon_inode:[ksu_driver]";
        auto length = std::min(capacity, strlen(name));
        memcpy(buffer, name, length);
        return static_cast<ssize_t>(length);
    }
    return __real_readlink(path, buffer, capacity);
}

extern "C" int __wrap_ioctl(int descriptor, unsigned long command, ...) {
    if (!permitted || descriptor != current_driver || command != KSU_IOCTL_GET_INFO) {
        errno = EPERM;
        return -1;
    }
    va_list arguments;
    va_start(arguments, command);
    auto info = va_arg(arguments, ksu_get_info_cmd *);
    va_end(arguments);
    *info = {.version = 32802, .flags = current_flags, .features = 0, .uapi_version = 6};
    return 0;
}

int main() {
    current_driver = open("/dev/null", O_RDONLY);
    assert(current_driver >= 0);
    assert(!is_manager());
    current_flags = KSU_GET_INFO_FLAG_MANAGER;
    assert(is_manager());
    assert(get_kernel_uapi_version() == 6);

    const auto old_descriptor = current_driver;
    current_driver = open("/dev/null", O_RDONLY);
    assert(current_driver >= 0 && current_driver != old_descriptor);
    assert(is_manager());
    assert(fd == current_driver);

    permitted = false;
    assert(get_info().version == 0);
    assert(get_info().flags == 0);
    assert(!is_manager());
    close(old_descriptor);
    close(current_driver);
}
