package io.milksu.manager.data.system

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ManagerApk {
    private const val LIMIT = 128L * 1024 * 1024
    private val random = SecureRandom()

    fun randomPackage(): String = randomPackage(random)

    internal fun randomPackage(random: SecureRandom): String = generateSequence {
        packageCandidate(random)
    }.first(ManagerAppearance::isHiddenTextAllowed)

    private fun packageCandidate(random: SecureRandom): String = List(3) {
        buildString {
            append(('a'.code + random.nextInt(26)).toChar())
            repeat(7 + random.nextInt(7)) {
                append("abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)])
            }
        }
    }.joinToString(".")

    fun createKey(file: File) {
        require(!file.exists())
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val name = X500Name("CN=Android")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger(128, random), Date(now - 86_400_000L),
                Date(now + 30L * 365 * 86_400_000L), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
        )
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("manager", pair.private, charArrayOf(), arrayOf(certificate))
            file.outputStream().use { store(it, charArrayOf()) }
        }
    }

    fun certificate(file: File): X509Certificate = keyStore(file).getCertificate("manager") as X509Certificate

    fun certificateHash(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private fun keyStore(file: File) = KeyStore.getInstance("PKCS12").apply {
        file.inputStream().use { load(it, charArrayOf()) }
    }

    fun verifiedSigner(file: File): X509Certificate {
        require(file.length() in 1..LIMIT)
        val verified = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(31).build().verify()
        require(verified.isVerified && verified.signerCertificates.size == 1) { "APK signature verification failed" }
        return verified.signerCertificates.single()
    }

    @JvmOverloads
    fun repackage(
        input: File, output: File, originalPackage: String, targetPackage: String, key: File,
        appearance: ManagerAppearance? = null, icon: ByteArray? = null, originalLabel: String? = null,
    ) {
        require(input.length() in 1..LIMIT && !output.exists())
        require((appearance?.iconEntry != null) == (icon != null))
        require(icon == null || (icon.size in 8..2_000_000 &&
            icon.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))))
        val unsigned = File.createTempFile("manager-", ".unsigned", output.parentFile)
        try {
            ZipFile(input).use { source ->
                val names = mutableSetOf<String>()
                var total = 0L
                var iconReplaced = false
                val counter = CountingStream(unsigned.outputStream())
                ZipOutputStream(counter).use { target ->
                    source.entries().asSequence().forEach { entry ->
                        require(names.add(entry.name) && !entry.name.startsWith("/") &&
                            entry.name.split('/').none { it == ".." })
                        if (entry.isDirectory || entry.name.startsWith("META-INF/")) return@forEach
                        require(entry.size in 0..LIMIT && total + entry.size <= LIMIT)
                        total += entry.size
                        val data = source.getInputStream(entry).use { stream ->
                            val bytes = ByteArray(entry.size.toInt())
                            var read = 0
                            while (read < bytes.size) {
                                val count = stream.read(bytes, read, bytes.size - read)
                                require(count > 0) { "Truncated APK entry" }
                                read += count
                            }
                            require(stream.read() == -1) { "Invalid APK entry size" }
                            bytes
                        }
                        val bytes = when (entry.name) {
                            "AndroidManifest.xml" -> ManagerManifest.rename(
                                data, originalPackage, targetPackage, appearance?.displayName)
                            "resources.arsc" -> if (appearance != null && originalLabel != null)
                                ManagerResources.renameLabel(data, originalLabel, appearance.displayName) else data
                            appearance?.iconEntry -> requireNotNull(icon).also { iconReplaced = true }
                            else -> data
                        }
                        val copied = ZipEntry(entry.name).apply {
                            time = entry.time
                            if (entry.method == ZipEntry.STORED) {
                                method = ZipEntry.STORED
                                size = bytes.size.toLong()
                                crc = CRC32().apply { update(bytes) }.value
                                val base = counter.bytes + 30 + name.toByteArray(Charsets.UTF_8).size
                                val padding = ((4 - base % 4) % 4).toInt()
                                if (padding != 0) {
                                    extra = ByteArray(4 + padding).also {
                                        it[0] = 0x35
                                        it[1] = 0xd9.toByte()
                                        it[2] = padding.toByte()
                                    }
                                }
                            }
                        }
                        target.putNextEntry(copied)
                        target.write(bytes)
                        target.closeEntry()
                    }
                    require(icon == null || iconReplaced) { "The Manager icon asset is missing" }
                }
            }
            val store = keyStore(key)
            val certificate = store.getCertificate("manager") as X509Certificate
            val signer = ApkSigner.SignerConfig.Builder(
                "manager", store.getKey("manager", charArrayOf()) as PrivateKey, listOf(certificate),
            ).build()
            ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(output)
                .setMinSdkVersion(31).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .setV3SigningEnabled(false).setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false).build().sign()
            require(certificateHash(verifiedSigner(output)) == certificateHash(certificate))
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            unsigned.delete()
        }
    }

    private class CountingStream(output: OutputStream) : FilterOutputStream(output) {
        var bytes = 0L
            private set
        override fun write(value: Int) { out.write(value); bytes++ }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            out.write(value, offset, length)
            bytes += length
        }
    }
}
