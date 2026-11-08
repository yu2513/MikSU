package io.milksu.manager.ui.screen.home

import io.milksu.manager.KernelVersion
import io.milksu.manager.ui.util.module.LatestVersionInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKernelUpdateTest {
    private val state = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 141),
        ksuVersion = 32800,
        managerUAPIVersion = 6,
        kernelUAPIVersion = 6,
        lkmMode = true,
        isLkmBundled = true,
        isManager = true,
        isManagerPrBuild = false,
        isKernelPrBuild = false,
        requiresNewKernel = false,
        requiresNewManager = false,
        isRootAvailable = true,
        isSafeMode = false,
        isLateLoadMode = false,
        checkUpdateEnabled = true,
        latestVersionInfo = LatestVersionInfo(),
        currentManagerVersionCode = 32801,
        bundledKernelVersionCode = 32800,
        systemInfo = SystemInfo("6.1.141", "0.2.1 (32800-6)", "", "", "Enforcing", 2),
    )

    @Test fun managerOnlyUpdateDoesNotRequestRootReinstallation() {
        assertFalse(state.showLkmUpdate)
        assertFalse(state.copy(currentManagerVersionCode = 32802).showLkmUpdate)
    }

    @Test fun changedBundledRootStillUsesTheExistingUpdateNotice() {
        assertTrue(state.copy(bundledKernelVersionCode = 32801).showLkmUpdate)
        assertFalse(state.copy(bundledKernelVersionCode = 32801, isLkmBundled = false).showLkmUpdate)
    }

    @Test fun release023Reuses32802KernelWithoutAnInstallationPrompt() {
        val upgradedManager = state.copy(
            ksuVersion = 32802,
            currentManagerVersionCode = 32803,
            bundledKernelVersionCode = 32802,
        )
        assertFalse(upgradedManager.showLkmUpdate)
        assertTrue(upgradedManager.copy(ksuVersion = 32801).showLkmUpdate)
    }

    @Test fun managerUpdatesUseTheirCodeWithoutRequestingANewRootImage() {
        val offered = state.copy(
            currentManagerVersionCode = 32800,
            latestVersionInfo = LatestVersionInfo(versionCode = 32801, version = "0.2.1"),
        )
        assertTrue(offered.hasUpdate)
        assertFalse(offered.showLkmUpdate)
        assertFalse(offered.copy(currentManagerVersionCode = 32801).hasUpdate)
    }
}
