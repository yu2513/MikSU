package io.milksu.manager.ui.util

import org.junit.Assert.*
import org.junit.Test

class RootShellCacheTest {
    private class Session(val root: Boolean, var alive: Boolean = true) {
        var closed = false
    }

    @Test fun temporaryRootFailureIsRetriedWithoutRestartingTheApp() {
        var time = 0L
        var rootAvailable = false
        var starts = 0
        val cache = RootShellCache(
            create = { starts++; Session(rootAvailable) },
            alive = { it.alive }, root = { it.root },
            close = { it.closed = true }, now = { time },
        )
        val fallback = cache.get()
        assertFalse(fallback.root)
        rootAvailable = true
        time = 1_999L
        assertSame(fallback, cache.get())
        assertEquals(1, starts)
        time = 2_000L
        val recovered = cache.get()
        assertTrue(recovered.root)
        assertTrue(fallback.closed)
        assertEquals(2, starts)
        time = 60_000L
        assertSame(recovered, cache.get())
    }

    @Test fun aDeadRootShellIsReplacedImmediately() {
        var starts = 0
        val cache = RootShellCache(
            create = { starts++; Session(true) },
            alive = { it.alive }, root = { it.root },
            close = { it.closed = true }, now = { 0L },
        )
        val first = cache.get()
        first.alive = false
        assertNotSame(first, cache.get())
        assertTrue(first.closed)
        assertEquals(2, starts)
    }

    @Test fun factoryFailureDoesNotPoisonSubsequentRequests() {
        var starts = 0
        val cache = RootShellCache(
            create = {
                if (++starts == 1) throw IllegalStateException("daemon is being replaced")
                Session(true)
            },
            alive = { it.alive }, root = { it.root },
            close = { it.closed = true }, now = { 0L },
        )
        assertTrue(runCatching { cache.get() }.isFailure)
        assertTrue(cache.get().root)
        assertEquals(2, starts)
    }
}
