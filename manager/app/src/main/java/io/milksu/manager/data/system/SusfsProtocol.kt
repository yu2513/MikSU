package io.milksu.manager.data.system

import kotlinx.serialization.Serializable

@Serializable
data class SusfsStatus(
    val supported: Boolean,
    val loaded: Boolean,
    val builtin: Boolean,
    val autoload: Boolean,
    val version: String? = null,
    val kmi: String? = null,
    val reason: String? = null,
    val variant: String? = null,
)

object SusfsProtocol {
    suspend fun disableIntegration(executor: CommandExecutor): SusfsStatus {
        checkCommand(executor.execute(listOf("susfs", "autoload", "disable")))
        var status = decodeStatus(executor.execute(listOf("susfs", "status")))
        if (status.loaded && !status.builtin) {
            checkCommand(executor.execute(listOf("susfs", "unload")))
            status = decodeStatus(executor.execute(listOf("susfs", "status")))
        }
        if (status.autoload || (status.loaded && !status.builtin)) {
            throw BackendException("susfs_still_running", status.reason ?: "SUSFS deactivation was not confirmed")
        }
        return status
    }

    suspend fun enableAutoload(executor: CommandExecutor, kmi: String): SusfsStatus {
        if (!kmi.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw BackendException("invalid_kmi", "Select a valid KMI")
        }
        var status = decodeStatus(executor.execute(listOf("susfs", "status")))
        if (!status.loaded && !status.builtin) {
            checkCommand(executor.execute(listOf("susfs", "load", "--kmi", kmi)))
            status = decodeStatus(executor.execute(listOf("susfs", "status")))
        }
        if (!status.supported || (!status.loaded && !status.builtin)) {
            throw BackendException("susfs_not_running", status.reason ?: "SUSFS did not start")
        }
        checkCommand(executor.execute(listOf("susfs", "autoload", "enable", "--kmi", kmi)))
        val confirmed = decodeStatus(executor.execute(listOf("susfs", "status")))
        if (!confirmed.autoload || !confirmed.supported || (!confirmed.loaded && !confirmed.builtin)) {
            throw BackendException("susfs_not_running", confirmed.reason ?: "SUSFS activation was not confirmed")
        }
        return confirmed
    }

    fun decodeStatus(output: CommandOutput): SusfsStatus {
        checkCommand(output)
        return try {
            KpmProtocol.json.decodeFromString<SusfsStatus>(output.stdout)
        } catch (e: Exception) {
            throw BackendException("invalid_susfs_status", e.message ?: "Invalid SUSFS status", output.exitCode, output.stderr)
        }
    }

    fun checkCommand(output: CommandOutput): CommandOutput {
        if (output.exitCode != 0) {
            throw BackendException(
                "susfs_command", output.stdout.ifBlank { "SUSFS command failed" },
                output.exitCode, output.stderr,
            )
        }
        return output
    }
}
