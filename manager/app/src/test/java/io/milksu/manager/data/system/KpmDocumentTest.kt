package io.milksu.manager.data.system

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class KpmDocumentTest {
    @Test fun readsAStreamWithoutAFileSystemPath() {
        val data = byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
        val output = ByteArrayOutputStream()
        KpmDocument.copyBounded(data.inputStream(), output, 4)
        assertArrayEquals(data, output.toByteArray())
    }

    @Test fun rejectsEmptyAndOversizedDocuments() {
        assertThrows(BackendException::class.java) {
            KpmDocument.copyBounded(byteArrayOf().inputStream(), ByteArrayOutputStream())
        }
        assertThrows(BackendException::class.java) {
            KpmDocument.copyBounded(ByteArray(5).inputStream(), ByteArrayOutputStream(), 4)
        }
    }
}
