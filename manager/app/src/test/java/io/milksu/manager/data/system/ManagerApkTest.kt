package io.milksu.manager.data.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom

class ManagerApkTest {
    @Test
    fun generatedIdentityUsesThePlatformRsaImplementationAndPersists() {
        val directory = Files.createTempDirectory("manager-identity-test").toFile()
        try {
            val file = File(directory, "identity.p12")
            ManagerApk.createKey(file)
            val certificate = ManagerApk.certificate(file)
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
            assertTrue(certificate.encoded.size in 1..1024)
            val store = KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, charArrayOf()) }
            }
            val data = "manager-transfer-test".toByteArray()
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(store.getKey("manager", charArrayOf()) as PrivateKey)
                update(data)
            }.sign()
            assertTrue(Signature.getInstance("SHA256withRSA").run {
                initVerify(certificate)
                update(data)
                verify(signature)
            })
            assertEquals(ManagerApk.certificateHash(certificate),
                ManagerApk.certificateHash(ManagerApk.certificate(file)))
            assertThrows(IllegalArgumentException::class.java) { ManagerApk.createKey(file) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun randomPackagesAreValidDistinctAndHaveNoFixedNamespace() {
        val packages = List(4096) { ManagerApk.randomPackage() }
        assertEquals(packages.size, packages.toSet().size)
        assertTrue(packages.all(ManagerManifest::validPackage))
        assertTrue(packages.all(ManagerAppearance::isHiddenTextAllowed))
        assertTrue(packages.map { it.substringBefore('.') }.toSet().size > 120)
    }

    @Test
    fun forbiddenFragmentsInAnyPartOfRandomPackageAreRetried() {
        val safe = listOf("abcdefgh", "jklmnopq", "rstvwxyz")
        val unsafe = listOf(
            listOf("abcsudef", safe[1], safe[2]),
            listOf(safe[0], "abcdioef", safe[2]),
            listOf(safe[0], safe[1], "xmanagerx"),
        )
        for (candidate in unsafe) {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val draws = (candidate + safe).flatMap { segment ->
                listOf(segment.first() - 'a', segment.length - 8) +
                    segment.drop(1).map { alphabet.indexOf(it) }
            }
            val scripted = object : SecureRandom() {
                var consumed = 0
                override fun nextInt(bound: Int): Int =
                    draws[consumed++].also { require(it in 0 until bound) }
            }
            assertEquals(safe.joinToString("."), ManagerApk.randomPackage(scripted))
            assertEquals(draws.size, scripted.consumed)
        }
    }
}
