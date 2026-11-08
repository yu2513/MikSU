package io.milksu.manager.ui.util

internal class RootShellCache<T>(
    private val create: () -> T,
    private val alive: (T) -> Boolean,
    private val root: (T) -> Boolean,
    private val close: (T) -> Unit,
    private val now: () -> Long,
    private val retryDelayMillis: Long = 2_000L,
) {
    private var cached: T? = null
    private var attemptedAt = 0L

    @Synchronized
    fun get(): T {
        val time = now()
        cached?.let {
            if (alive(it) && (root(it) || time - attemptedAt in 0 until retryDelayMillis)) return it
            runCatching { close(it) }
            cached = null
        }
        return create().also {
            cached = it
            attemptedAt = now()
        }
    }
}
