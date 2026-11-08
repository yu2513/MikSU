package io.milksu.manager.data.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.topjohnwu.superuser.Shell
import io.milksu.manager.data.script.ScriptCommand
import io.milksu.manager.ksuApp
import io.milksu.manager.ui.util.getRootShell
import io.milksu.manager.ui.util.withNewRootShell
import java.io.File

class RootCommandExecutor(private val isolatedShell: Boolean = false) : CommandExecutor {
    override suspend fun execute(arguments: List<String>): CommandOutput = withContext(Dispatchers.IO) {
        if (isolatedShell) {
            withNewRootShell(globalMnt = true) { execute(this, arguments) }
        } else {
            execute(getRootShell(true), arguments)
        }
    }

    private fun execute(shell: Shell, arguments: List<String>): CommandOutput {
        if (!shell.isRoot) throw BackendException("root_unavailable", "Root access is required")
        val daemon = File(ksuApp.applicationInfo.nativeLibraryDir, "libksud.so")
        val command = (listOf(daemon.absolutePath) + arguments).joinToString(" ", transform = ScriptCommand::quote)
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val result = shell.newJob().add(command).to(stdout, stderr).exec()
        return CommandOutput(result.code, stdout.joinToString("\n"), stderr.joinToString("\n"))
    }
}

object SystemBackends {
    val executor: CommandExecutor by lazy { RootCommandExecutor() }
    val integrationVerifier: CommandExecutor by lazy { RootCommandExecutor(isolatedShell = true) }
    val kpm: KpmRepository by lazy { KpmRepository(executor) }
}
