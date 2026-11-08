#ifndef __KSU_H_UID_OBSERVER
#define __KSU_H_UID_OBSERVER

#include <linux/types.h>
#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline void ksu_throne_tracker_init()
{
}

static inline void ksu_throne_tracker_exit()
{
}

static inline int track_throne(bool prune_only)
{
    (void)prune_only;
    return 0;
}
#else
void ksu_throne_tracker_init();

void ksu_throne_tracker_exit();

int track_throne(bool prune_only);
#endif

#endif
