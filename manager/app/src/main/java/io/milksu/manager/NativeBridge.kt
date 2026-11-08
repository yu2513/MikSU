package io.milksu.manager

import androidx.annotation.Keep

@Keep
internal object NativeBridge {
    val version: Int external get
    val kernelUAPIVersion: Int external get
    val managerUAPIVersion: Int external get
    val isSafeMode: Boolean external get
    val isLkmMode: Boolean external get
    val isLkmBundled: Boolean external get
    val isLateLoadMode: Boolean external get
    val isManager: Boolean external get
    val isManagerHideSupported: Boolean external get
    val isPrBuild: Boolean external get

    external fun verifyManagerAccess(): Boolean
    external fun uidShouldUmount(uid: Int): Boolean
    external fun getAppProfile(key: String?, uid: Int): Natives.Profile
    external fun setAppProfile(profile: Natives.Profile?): Boolean
    external fun isSuEnabled(): Boolean
    external fun setSuEnabled(enabled: Boolean): Boolean
    external fun isKernelUmountEnabled(): Boolean
    external fun setKernelUmountEnabled(enabled: Boolean): Boolean
    external fun isSelinuxHideEnabled(): Boolean
    external fun setSelinuxHideEnabled(enabled: Boolean): Int
    external fun getUserName(uid: Int): String?
    external fun getSuperuserCount(): Int
}
