package app.pocketpilot.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.pocketpilot.network.api.SecretStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with AES-GCM under a key held by the Android Keystore (spec section 9: wrapped
 * blobs under `pp.secret.<name>`). The encrypted blobs live in no-backup storage, so neither they nor
 * the key leave the phone in a backup or device transfer.
 */
class KeystoreSecretStore(
    directory: File,
) : SecretStore {
    private val dir = File(directory, "secrets").apply { mkdirs() }

    @Synchronized
    override fun get(name: String): String? {
        val blob = runCatching { file(name).readBytes() }.getOrNull() ?: return null
        if (blob.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).decodeToString()
        }.getOrNull()
    }

    @Synchronized
    override fun put(
        name: String,
        value: String,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(value.encodeToByteArray())
        val tmp = File(dir, "${file(name).name}.tmp")
        tmp.writeBytes(cipher.iv + sealed)
        tmp.renameTo(file(name))
    }

    @Synchronized
    override fun remove(name: String) {
        file(name).delete()
    }

    private fun file(name: String): File {
        require(NAME.matches(name)) { "Bad secret name: $name" }
        return File(dir, name)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pp.secret.wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        val NAME = Regex("[a-z0-9_]+")
    }
}
