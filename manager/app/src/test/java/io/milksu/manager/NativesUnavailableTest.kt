package io.milksu.manager

import org.junit.Assert.*
import org.junit.Test

class NativesUnavailableTest {
    @Test fun missingNativeLibraryDoesNotCrashOrReportPrivileges() {
        assertFalse(Natives.isLoaded)
        assertNotNull(Natives.loadError)
        assertEquals(0, Natives.version)
        assertEquals(0, Natives.kernelUAPIVersion)
        assertFalse(Natives.isManager)
        assertFalse(Natives.isManagerHideSupported)
        assertFalse(Natives.verifyManagerAccess())
        assertFalse(Natives.isFullFeatured())
        assertTrue(Natives.uidShouldUmount(10001))
        assertNull(Natives.getUserName(10001))
        assertEquals(0, Natives.getSuperuserCount())
        assertFalse(Natives.setSuEnabled(true))
        assertFalse(Natives.setKernelUmountEnabled(true))
        assertEquals(-19, Natives.setSelinuxHideEnabled(true))
        val profile = Natives.getAppProfile("example.app", 10001)
        assertEquals(10001, profile.currentUid)
        assertFalse(profile.allowSu)
        assertFalse(Natives.setAppProfile(profile))
    }
}
