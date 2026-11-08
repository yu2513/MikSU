package io.milksu.manager.data.system

import java.io.InputStream
import java.io.OutputStream

object KpmDocument {
    const val MAX_BYTES = 32L * 1024 * 1024

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_BYTES) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BackendException("file_too_large", "KPM exceeds the import size limit")
            output.write(buffer, 0, count)
        }
        if (total == 0L) throw BackendException("empty_file", "The selected file is empty")
    }
}
