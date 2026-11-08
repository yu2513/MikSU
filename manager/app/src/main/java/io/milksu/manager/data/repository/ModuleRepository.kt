package io.milksu.manager.data.repository

import io.milksu.manager.data.model.Module
import io.milksu.manager.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
