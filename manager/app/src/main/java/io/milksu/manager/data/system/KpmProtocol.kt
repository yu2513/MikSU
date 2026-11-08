package io.milksu.manager.data.system

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

data class CommandOutput(val exitCode: Int, val stdout: String, val stderr: String)

class BackendException(
    val code: String,
    message: String,
    val exitCode: Int? = null,
    val stderr: String = "",
) : IOException(message) {
    val display: String
        get() = buildString {
            append(code).append(": ").append(message)
            exitCode?.let { append(" (exit ").append(it).append(')') }
            if (stderr.isNotBlank()) append('\n').append(stderr.take(4096))
        }
}

@Serializable
data class KpmStatus(
    val supported: Boolean,
    @SerialName("configured_enabled") val configuredEnabled: Boolean,
    val active: Boolean,
    @SerialName("reboot_required") val rebootRequired: Boolean,
    val version: String? = null,
    val reason: String? = null,
    @SerialName("transition_failed") val transitionFailed: Boolean = false,
    @SerialName("can_configure") val canConfigure: Boolean? = null,
    @SerialName("state_known") val stateKnown: Boolean = true,
    @SerialName("upgrade_available") val upgradeAvailable: Boolean = false,
    @SerialName("can_enable") val canEnable: Boolean? = null,
    @SerialName("can_disable") val canDisable: Boolean? = null,
    @SerialName("integration_verified") val integrationVerified: Boolean = true,
    @SerialName("can_unload") val canUnload: Boolean = false,
    @SerialName("engine_sha256") val engineSha256: String? = null,
) {
    fun sameIntegrationState(other: KpmStatus): Boolean =
        stateKnown && other.stateKnown &&
            configuredEnabled == other.configuredEnabled && active == other.active &&
            rebootRequired == other.rebootRequired && transitionFailed == other.transitionFailed &&
            supported == other.supported && version == other.version && engineSha256 == other.engineSha256
}

@Serializable
data class KpmModule(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val args: String,
    val sha256: String,
    val autoload: Boolean,
    val loaded: Boolean,
    @SerialName("pending_unload") val pendingUnload: Boolean,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("has_control") val hasControl: Boolean,
)

enum class IntegrationState { READING, OFF, PENDING_REBOOT, ACTIVE, UNAVAILABLE, ERROR }

fun integrationState(
    status: KpmStatus?,
    unavailable: String? = null,
    error: BackendException? = null,
): IntegrationState = when {
    unavailable != null -> IntegrationState.UNAVAILABLE
    error != null -> IntegrationState.ERROR
    status == null -> IntegrationState.READING
    !status.supported -> IntegrationState.UNAVAILABLE
    status.transitionFailed -> IntegrationState.ERROR
    status.rebootRequired -> IntegrationState.PENDING_REBOOT
    status.configuredEnabled != status.active -> IntegrationState.ERROR
    status.active -> IntegrationState.ACTIVE
    else -> IntegrationState.OFF
}

object KpmProtocol {
    val json = Json { ignoreUnknownKeys = true }

    fun unwrap(output: CommandOutput): JsonElement {
        val envelope = try {
            json.parseToJsonElement(output.stdout) as? JsonObject
                ?: throw IllegalArgumentException("Expected an object")
        } catch (e: Exception) {
            throw BackendException(
                "invalid_response", "Invalid backend JSON: ${e.message}", output.exitCode,
                output.stderr.ifBlank { output.stdout.take(4096) },
            )
        }
        val ok = (envelope["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw BackendException("invalid_response", "Missing boolean ok", output.exitCode, output.stderr)
        val error = envelope["error"]
        if (error == null || (error != JsonNull && error !is JsonObject)) {
            throw BackendException("invalid_response", "Missing or invalid error field", output.exitCode, output.stderr)
        }
        if (!ok || error != JsonNull || output.exitCode != 0) {
            val detail = error as? JsonObject
            throw BackendException(
                (detail?.get("code") as? JsonPrimitive)?.contentOrNull ?: "exit_${output.exitCode}",
                (detail?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Backend command failed",
                output.exitCode, output.stderr,
            )
        }
        return envelope["data"]
            ?: throw BackendException("invalid_response", "Missing data field", output.exitCode, output.stderr)
    }

    inline fun <reified T> decode(output: CommandOutput): T = try {
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), unwrap(output))
    } catch (e: BackendException) {
        throw e
    } catch (e: Exception) {
        throw BackendException("invalid_data", e.message ?: "Invalid backend data", output.exitCode, output.stderr)
    }

}
