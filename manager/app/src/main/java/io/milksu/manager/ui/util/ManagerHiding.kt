package io.milksu.manager.ui.util

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.net.Uri
import android.util.AtomicFile
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.milksu.manager.BuildConfig
import io.milksu.manager.Natives
import io.milksu.manager.R
import io.milksu.manager.data.repository.SettingsRepositoryImpl
import io.milksu.manager.data.script.ScriptCommand
import io.milksu.manager.data.system.CoreApkIdentity
import io.milksu.manager.data.system.CoreManagerUpdatePolicy
import io.milksu.manager.data.system.ManagerApk
import io.milksu.manager.data.system.ManagerAppearance
import io.milksu.manager.data.system.ManagerIcon
import io.milksu.manager.data.system.ManagerManifest
import io.milksu.manager.data.system.ManagerTransfer
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.MainActivity
import io.milksu.manager.ui.util.module.LatestVersionInfo
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

data class ManagerHideState(
    val supported: Boolean = false,
    val hidden: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

object ManagerHiding {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operation = Mutex()
    private val handoverRunning = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ManagerHideState(
        supported = Process.myUid() in 10000..99999 &&
            runCatching { Natives.isManagerHideSupported }.getOrDefault(false),
        hidden = ksuApp.packageName != BuildConfig.APPLICATION_ID,
    ))
    val state = mutableState.asStateFlow()
    val isHidden get() = ksuApp.packageName != BuildConfig.APPLICATION_ID
    private val directory get() = File(ksuApp.filesDir, "manager_hiding")
    private val officialApk get() = File(directory, "official.apk")
    private val pendingOfficialApk get() = File(directory, "official.pending.apk")
    private val localKey get() = File(directory, "identity.p12")
    private val transferFile get() = File(directory, "transfer.json")
    private val readyFile get() = File(directory, "ready")
    private val authorizationFile get() = File(directory, "authorized")
    private val failureFile get() = File(directory, "failure")
    private val appearanceFile get() = File(directory, "appearance.json")
    private val customIcon get() = File(directory, "icon.png")
    private val json = Json { ignoreUnknownKeys = false }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            runCatching { promoteOfficialUpdate() }
            val supported = Process.myUid() in 10000..99999 &&
                runCatching { Natives.isManagerHideSupported }.getOrDefault(false)
            mutableState.update { it.copy(supported = supported, hidden = isHidden) }
        }
    }

    fun checkSupport(): Boolean {
        val supported = Process.myUid() in 10000..99999 &&
            runCatching { Natives.isManagerHideSupported }.getOrDefault(false)
        mutableState.update { it.copy(supported = supported, hidden = isHidden) }
        if (!supported) Toast.makeText(ksuApp, R.string.settings_manager_hide_unsupported, Toast.LENGTH_LONG).show()
        return supported
    }

    fun setHidden(hidden: Boolean, displayName: String? = null, iconUri: Uri? = null) {
        scope.launch {
            if (!operation.tryLock()) return@launch
            try {
                require(hidden != isHidden)
                if (!checkSupport()) return@launch
                if (hidden) ManagerAppearance.validateHiddenName(requireNotNull(displayName))
                mutableState.update { it.copy(busy = true, error = null) }
                withContext(Dispatchers.IO) { withTimeout(150_000L) { migrate(hidden, displayName, iconUri) } }
            } catch (error: Exception) {
                val message = error.message ?: "Manager transfer failed"
                mutableState.update { it.copy(error = message) }
                Toast.makeText(ksuApp, message, Toast.LENGTH_LONG).show()
            } finally {
                mutableState.update { it.copy(busy = false) }
                operation.unlock()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun archive(file: File): PackageInfo =
        ksuApp.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("The Manager APK cannot be inspected")

    private fun identity(info: PackageInfo) = CoreApkIdentity(
        packageName = info.packageName,
        versionCode = info.longVersionCode,
        versionName = info.versionName,
        signerSha256 = info.signingInfo?.apkContentsSigners?.map {
            hash(it.toByteArray())
        }?.toSet().orEmpty(),
        debuggable = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        testOnly = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_TEST_ONLY != 0,
        split = !info.splitNames.isNullOrEmpty(),
    )

    fun validateOfficialUpdate(file: File, release: LatestVersionInfo) {
        require(isHidden && officialApk.isFile)
        val installed = identity(archive(officialApk)).copy(versionCode = BuildConfig.VERSION_CODE.toLong())
        val candidate = identity(archive(file))
        require(ManagerApk.certificateHash(ManagerApk.verifiedSigner(file)) == installed.signerSha256.single())
        CoreManagerUpdatePolicy.validateIdentity(installed, candidate, release)
    }

    suspend fun repackageUpdate(file: File): File = withContext(Dispatchers.IO) {
        operation.withLock {
            val target = File.createTempFile("manager-update-", ".apk", ksuApp.cacheDir).also { it.delete() }
            try {
                val saved = if (appearanceFile.isFile) json.decodeFromString<ManagerAppearance>(appearanceFile.readText()) else null
                val application = archiveApplication(file)
                val appearance = saved?.copy(iconEntry = if (customIcon.isFile) ManagerIcon.entry(ksuApp, application) else null)
                ManagerApk.repackage(file, target, BuildConfig.APPLICATION_ID, ksuApp.packageName, localKey,
                    appearance, if (customIcon.isFile) customIcon.readBytes() else null,
                    application.loadLabel(ksuApp.packageManager).toString())
                require(identity(archive(target)).packageName == ksuApp.packageName)
                val installedHash = ManagerApk.certificateHash(ManagerApk.certificate(localKey))
                require(ManagerApk.certificateHash(ManagerApk.verifiedSigner(target)) == installedHash)
                atomicCopy(file, pendingOfficialApk)
                target
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
    }

    private suspend fun migrate(hidden: Boolean, displayName: String?, iconUri: Uri?) {
        promoteOfficialUpdate()
        if (recoverAbandonedSourceTransfer()) return
        val self = ksuApp.packageName
        require(ManagerManifest.validPackage(self))
        val source = File(ksuApp.applicationInfo.sourceDir)
        val original = if (hidden) source else officialApk
        val sourceIdentity = identity(archive(source))
        require(sourceIdentity.packageName == self)
        require(original.isFile && identity(archive(original)).packageName == BuildConfig.APPLICATION_ID)
        val target = if (hidden) generateSequence { ManagerApk.randomPackage() }
            .first { installedPath(it) == null } else BuildConfig.APPLICATION_ID
        require(installedPath(target) == null) { "The target Manager is already installed" }
        val staged = if (hidden) File.createTempFile("manager-hidden-", ".apk", ksuApp.cacheDir).also { it.delete() }
            else original
        var installed = false
        var removalAuthorized = false
        var transfer: ManagerTransfer? = null
        try {
            if (hidden) {
                require(directory.isDirectory || directory.mkdirs()) { "The private Manager directory is unavailable" }
                val icon = ManagerIcon.png(ksuApp, iconUri)
                val application = archiveApplication(source)
                val appearance = ManagerAppearance(
                    ManagerAppearance.validateHiddenName(requireNotNull(displayName)),
                    ManagerIcon.entry(ksuApp, application),
                )
                writeAtomic(appearanceFile, json.encodeToString(appearance).toByteArray())
                writeAtomic(customIcon, icon)
                localKey.delete()
                ManagerApk.createKey(localKey)
                atomicCopy(source, officialApk)
                ManagerApk.repackage(source, staged, self, target, localKey, appearance, icon,
                    application.loadLabel(ksuApp.packageManager).toString())
            }
            val candidate = identity(archive(staged))
            require(candidate.packageName == target && candidate.versionCode >= sourceIdentity.versionCode &&
                !candidate.debuggable && !candidate.testOnly && !candidate.split)
            val certificate = ManagerApk.verifiedSigner(staged)
            val signer = ManagerApk.certificateHash(certificate)
            require(candidate.signerSha256 == setOf(signer))
            val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            transfer = ManagerTransfer(
                token, self, target, Process.myUid(), 0, candidate.versionCode,
                candidate.versionName ?: throw IOException("Missing Manager version"),
                sourceIdentity.signerSha256.single(), signer, hidden, System.currentTimeMillis(),
            )
            writeAtomic(transferFile, json.encodeToString(transfer).toByteArray())
            val result = root("pm install -r --user 0 -S ${staged.length()} < ${quote(staged.absolutePath)}")
            require("Success" in result) { "The replacement Manager was not installed" }
            installed = true
            val actualPath = installedPath(target) ?: throw IOException("The installed Manager is missing")
            val targetUid = installedUid(target)
            transfer = transfer.copy(targetUid = targetUid)
            writeAtomic(transferFile, json.encodeToString(transfer).toByteArray())
            val actual = File(actualPath)
            val targetIdentity = identity(archive(actual))
            require(targetIdentity == candidate && hash(actual.readBytes()) == hash(staged.readBytes())) {
                "The installed Manager does not match the verified APK"
            }
            if (hidden) {
                val appearance = json.decodeFromString<ManagerAppearance>(appearanceFile.readText())
                require(archiveApplication(actual).loadLabel(ksuApp.packageManager).toString() == appearance.displayName)
                appearance.iconEntry?.let { entry ->
                    ZipFile(actual).use { apk ->
                        val image = apk.getInputStream(requireNotNull(apk.getEntry(entry))).use { it.readBytes() }
                        require(image.contentEquals(customIcon.readBytes()))
                    }
                }
            }
            readyFile.delete()
            authorizationFile.delete()
            failureFile.delete()
            val targetData = "/data/user/0/$target"
            root("test -d ${quote(targetData)} && test ! -L ${quote(targetData)} && " +
                "test \"\$(stat -c %u ${quote(targetData)})\" = $targetUid")
            val targetFiles = "$targetData/files/manager_hiding"
            root("mkdir -p ${quote(targetFiles)} ${quote("$targetData/shared_prefs")} && " +
                "chown $targetUid:$targetUid ${quote("$targetData/files")} && " +
                "chmod 700 ${quote("$targetData/files")} && restorecon -F ${quote("$targetData/files")}")
            val preferences = File(ksuApp.applicationInfo.dataDir, "shared_prefs")
            preferences.listFiles()?.filter { it.isFile && it.extension == "xml" }?.forEach {
                root("cp ${quote(it.absolutePath)} ${quote("$targetData/shared_prefs/${it.name}")}")
            }
            for (name in listOf("official.apk", "identity.p12", "transfer.json", "appearance.json", "icon.png")) {
                val input = File(directory, name)
                if (input.isFile) root("cp ${quote(input.absolutePath)} ${quote("$targetFiles/$name")}")
            }
            root("chown -R $targetUid:$targetUid ${quote(targetFiles)} ${quote("$targetData/shared_prefs")} && " +
                "chmod 700 ${quote(targetFiles)} ${quote("$targetData/shared_prefs")} && " +
                "restorecon -RF ${quote(targetFiles)} ${quote("$targetData/shared_prefs")}")
            if (hidden) {
                val record = File(directory, "identity-record")
                record.writeBytes(transfer.identityRecord(certificate.encoded.size))
                val deviceFiles = "/data/user_de/0/$target/files"
                val deviceRecord = "$deviceFiles/.manager-identity"
                root("mkdir -p ${quote(deviceFiles)} && chown $targetUid:$targetUid ${quote(deviceFiles)} && " +
                    "chmod 700 ${quote(deviceFiles)} && restorecon -F ${quote(deviceFiles)} && " +
                    "cp ${quote(record.absolutePath)} ${quote("$deviceRecord.new")} && " +
                    "chown 0:0 ${quote("$deviceRecord.new")} && chmod 600 ${quote("$deviceRecord.new")} && " +
                    "mv ${quote("$deviceRecord.new")} ${quote(deviceRecord)} && restorecon -F ${quote(deviceRecord)}")
                record.delete()
            }
            root("appops set --user 0 ${quote(target)} REQUEST_INSTALL_PACKAGES allow")
            refreshGlobalIdentity()
            launchTransfer(transfer, false)
            var ready = false
            repeat(100) {
                if (!ready) {
                    transferFailure(targetFiles, token)?.let { throw IOException(it) }
                    val response = rootOrNull("cat ${quote("$targetFiles/ready")}")
                    ready = response == token
                    if (!ready) delay(150)
                }
            }
            require(ready) { "The new Manager did not confirm its identity and root access; the original app was retained" }
            require(installedUid(target) == targetUid && installedPath(target) == actualPath)
            removalAuthorized = true
            writeAtomic(authorizationFile, token.toByteArray())
            root("printf %s ${quote(token)} > ${quote("$targetFiles/authorized.new")} && " +
                "chown $targetUid:$targetUid ${quote("$targetFiles/authorized.new")} && " +
                "chmod 600 ${quote("$targetFiles/authorized.new")} && " +
                "restorecon -F ${quote("$targetFiles/authorized.new")} && " +
                "mv ${quote("$targetFiles/authorized.new")} ${quote("$targetFiles/authorized")}")
            withTimeout(30_000L) {
                while (installedPath(self) != null) {
                    transferFailure(targetFiles, token)?.let { throw IOException(it) }
                    delay(250)
                }
            }
        } catch (error: Throwable) {
            if (removalAuthorized) throw error
            val cleanup = runCatching {
                if ((installed || installedPath(target) != null) && installedPath(self) != null) {
                    val targetIdentity = identity(archive(File(installedPath(target)
                        ?: throw IOException("The replacement Manager is missing"))))
                    require(targetIdentity.signerSha256 == setOf(transfer?.targetCertificate))
                    root("am force-stop --user 0 ${quote(target)}")
                    if (installedPath(self) != null) {
                        transfer?.takeIf { it.hidden }?.let {
                            root("rm -f ${quote("/data/user_de/0/$target/files/.manager-identity")}")
                            refreshGlobalIdentity()
                        }
                        root("pm uninstall --user 0 ${quote(target)}")
                        require(installedPath(target) == null)
                    }
                }
            }
            if (cleanup.isSuccess) {
                transferFile.delete()
                if (hidden) directory.deleteRecursively()
            }
            throw error
        } finally {
            if (hidden) staged.delete()
        }
    }

    private fun launchTransfer(transfer: ManagerTransfer, finalize: Boolean) {
        val flags = if (finalize) "-f 0x30000000 " else ""
        root("am start ${flags}--user 0 -n ${quote("${transfer.target}/${MainActivity::class.java.name}")}")
    }

    fun onMainReady() {
        if (!handoverRunning.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                if (!transferFile.isFile) return@launch
                runCatching {
                    val transfer = json.decodeFromString<ManagerTransfer>(transferFile.readText())
                    if (transfer.target != ksuApp.packageName) return@runCatching
                    val finalize = authorizationFile.isFile && authorizationFile.readText() == transfer.token
                    completeTransfer(transfer.token, finalize)
                    if (!finalize) {
                        withTimeout(45_000L) {
                            while (!authorizationFile.isFile || authorizationFile.readText() != transfer.token) delay(100)
                        }
                        completeTransfer(transfer.token, true)
                    }
                }.onFailure { error ->
                    recordTransferFailure(error)
                }
            } finally {
                handoverRunning.set(false)
            }
        }
    }

    fun recordTransferFailure(error: Throwable) {
        runCatching {
            val transfer = json.decodeFromString<ManagerTransfer>(transferFile.readText())
            if (transfer.target == ksuApp.packageName)
                writeAtomic(failureFile, "${transfer.token}:${error.message ?: "Manager transfer failed"}".toByteArray())
        }
    }

    suspend fun completeTransfer(nonce: String, finalize: Boolean) = withContext(Dispatchers.IO) {
        operation.withLock {
            if (transferFile.isFile) completeTransferLocked(nonce, finalize)
        }
    }

    private suspend fun completeTransferLocked(nonce: String, finalize: Boolean) {
        val transfer = json.decodeFromString<ManagerTransfer>(transferFile.readText())
        val committed = authorizationFile.isFile && authorizationFile.readText() == nonce &&
            installedPath(transfer.source) == null
        transfer.validate(nonce, ksuApp.packageName, Process.myUid(),
            if (committed) transfer.createdAt else System.currentTimeMillis())
        val self = identity(archive(File(ksuApp.applicationInfo.sourceDir)))
        require(self.packageName == transfer.target && self.versionCode == transfer.versionCode &&
            self.versionName == transfer.versionName && self.signerSha256 == setOf(transfer.targetCertificate))
        require(Natives.verifyManagerAccess()) {
            "The new Manager has no current kernel management access"
        }
        if (!finalize) {
            require(root("id -u").trim() == "0") { "The new Manager has no root access" }
            val receiver = ComponentName(ksuApp, "io.milksu.manager.magica.BootCompletedReceiver")
            val receiverState = if (SettingsRepositoryImpl().autoJailbreak)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ksuApp.packageManager.setComponentEnabledSetting(receiver, receiverState, PackageManager.DONT_KILL_APP)
            require(ksuApp.packageManager.getComponentEnabledSetting(receiver) == receiverState)
            writeAtomic(readyFile, nonce.toByteArray())
            return
        }
        require(readyFile.readText() == nonce && authorizationFile.readText() == nonce) {
            "The new Manager was not verified before removal"
        }
        require(transfer.hidden == (ksuApp.packageName != BuildConfig.APPLICATION_ID))
        val sourcePath = installedPath(transfer.source)
        if (sourcePath != null) {
            transfer.validate(nonce, ksuApp.packageName, Process.myUid(), System.currentTimeMillis())
            require(installedUid(transfer.source) == transfer.sourceUid)
            val source = identity(archive(File(sourcePath)))
            require(source.packageName == transfer.source && source.signerSha256 == setOf(transfer.sourceCertificate))
            require(Natives.verifyManagerAccess())
            root("pm uninstall --user 0 ${quote(transfer.source)}")
        }
        require(installedPath(transfer.source) == null) { "The original Manager could not be uninstalled" }
        require(installedUid(transfer.target) == Process.myUid())
        refreshGlobalIdentity()
        require(!transferFile.exists() || transferFile.delete()) { "The completed Manager transfer could not be cleared" }
        readyFile.delete()
        authorizationFile.delete()
        failureFile.delete()
        if (!transfer.hidden) directory.deleteRecursively()
        refresh()
    }

    private fun archiveApplication(file: File): ApplicationInfo =
        requireNotNull(archive(file).applicationInfo).apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }

    private fun transferFailure(targetFiles: String, token: String): String? =
        rootOrNull("cat ${quote("$targetFiles/failure")}")
            ?.takeIf { it.startsWith("$token:") }?.removePrefix("$token:")

    private suspend fun recoverAbandonedSourceTransfer(): Boolean {
        if (!transferFile.isFile) return false
        var transfer = json.decodeFromString<ManagerTransfer>(transferFile.readText())
        require(transfer.source == ksuApp.packageName && transfer.sourceUid == Process.myUid()) {
            "A Manager transfer is still pending"
        }
        val targetPath = installedPath(transfer.target)
        if (targetPath != null) {
            val targetUid = installedUid(transfer.target)
            require(transfer.targetUid == 0 || transfer.targetUid == targetUid)
            transfer.copy(targetUid = targetUid).validate(transfer.token, transfer.target, targetUid, transfer.createdAt)
            val target = identity(archive(File(targetPath)))
            require(target.signerSha256 == setOf(transfer.targetCertificate) &&
                target.versionCode == transfer.versionCode)
            if (authorizationFile.isFile) {
                require(authorizationFile.readText() == transfer.token)
                transfer = transfer.copy(createdAt = System.currentTimeMillis(), targetUid = targetUid)
                writeAtomic(transferFile, json.encodeToString(transfer).toByteArray())
                val targetFiles = "/data/user/0/${transfer.target}/files/manager_hiding"
                root("cp ${quote(transferFile.absolutePath)} ${quote("$targetFiles/transfer.json.new")} && " +
                    "chown $targetUid:$targetUid ${quote("$targetFiles/transfer.json.new")} && " +
                    "chmod 600 ${quote("$targetFiles/transfer.json.new")} && " +
                    "mv ${quote("$targetFiles/transfer.json.new")} ${quote("$targetFiles/transfer.json")} && " +
                    "printf %s ${quote(transfer.token)} > ${quote("$targetFiles/authorized.new")} && " +
                    "chown $targetUid:$targetUid ${quote("$targetFiles/authorized.new")} && " +
                    "chmod 600 ${quote("$targetFiles/authorized.new")} && " +
                    "restorecon -F ${quote("$targetFiles/authorized.new")} && " +
                    "mv ${quote("$targetFiles/authorized.new")} ${quote("$targetFiles/authorized")} && " +
                    "rm -f ${quote("$targetFiles/failure")} && restorecon -RF ${quote(targetFiles)}")
                launchTransfer(transfer, true)
                withTimeout(30_000L) {
                    while (installedPath(ksuApp.packageName) != null) {
                        transferFailure(targetFiles, transfer.token)?.let { throw IOException(it) }
                        delay(250)
                    }
                }
                return true
            }
            root("am force-stop --user 0 ${quote(transfer.target)}")
            require(installedPath(ksuApp.packageName) != null)
            root("pm uninstall --user 0 ${quote(transfer.target)}")
            refreshGlobalIdentity()
        }
        require(!authorizationFile.exists()) { "The verified replacement must be retained while removal completes" }
        require(transferFile.delete())
        return false
    }

    private fun promoteOfficialUpdate() {
        if (!isHidden || !pendingOfficialApk.isFile || !officialApk.isFile) return
        val pending = identity(archive(pendingOfficialApk))
        if (pending.versionCode != BuildConfig.VERSION_CODE.toLong() || pending.versionName != BuildConfig.VERSION_NAME) return
        val original = identity(archive(officialApk))
        require(pending.packageName == BuildConfig.APPLICATION_ID && pending.signerSha256 == original.signerSha256 &&
            setOf(ManagerApk.certificateHash(ManagerApk.verifiedSigner(pendingOfficialApk))) == original.signerSha256)
        atomicCopy(pendingOfficialApk, officialApk)
        pendingOfficialApk.delete()
    }

    private fun installedPath(packageName: String): String? {
        require(ManagerManifest.validPackage(packageName))
        if (installedUidOrNull(packageName) == null) return null
        val result = root("pm path --user 0 ${quote(packageName)}")
        return result.lineSequence().filter { it.startsWith("package:") }.singleOrNull()
            ?.removePrefix("package:")?.takeIf { it.startsWith("/data/app/") && it.endsWith("/base.apk") }
            ?: throw IOException("The installed Manager APK path is invalid")
    }

    private fun installedUid(packageName: String): Int =
        installedUidOrNull(packageName) ?: throw IOException("The installed Manager UID is missing")

    private fun installedUidOrNull(packageName: String): Int? {
        require(ManagerManifest.validPackage(packageName))
        val output = root("cmd package list packages -U --user 0 ${quote(packageName)}")
        if (output.isBlank()) return null
        val entries = output.lineSequence().map {
            Regex("package:([a-zA-Z0-9_.]+) uid:(\\d+)").matchEntire(it.trim())
                ?: throw IOException("The package service did not return a valid package list")
        }.filter { it.groupValues[1] == packageName }.toList()
        if (entries.isEmpty()) return null
        val uid = entries.singleOrNull()?.groupValues?.get(2)?.toIntOrNull()
        return uid?.takeIf { it in 10000..99999 } ?: throw IOException("The installed Manager UID is invalid")
    }

    private fun root(command: String): String = withNewRootShell(globalMnt = true) {
        require(isRoot) { "Root access is required" }
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val result = newJob().add(command).to(output, errors).exec()
        if (!result.isSuccess) throw IOException(errors.joinToString("\n").ifBlank { output.joinToString("\n") })
        output.joinToString("\n").trim()
    }

    private fun rootOrNull(command: String) = runCatching { root(command) }.getOrNull()
    private fun refreshGlobalIdentity() {
        root("${quote("${ksuApp.applicationInfo.nativeLibraryDir}/libksud.so")} debug refresh-manager")
    }
    private fun quote(value: String) = ScriptCommand.quote(value)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun writeAtomic(file: File, bytes: ByteArray) {
        require(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun atomicCopy(from: File, to: File) = writeAtomic(to, from.readBytes())
}
