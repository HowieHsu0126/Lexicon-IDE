package dev.lexicon.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The only persisted representation of the user's API key is authenticated ciphertext. */
internal class ApiKeyStore(
    context: Context,
    fileName: String = "deepseek-key.aesgcm",
    private val alias: String = "lexicon.deepseek.key.v1",
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    @Synchronized fun read(): String? {
        if (!file.baseFile.exists()) return null
        return try {
            val bytes = file.readFully()
            require(bytes.size >= 29 && bytes[0] == 1.toByte())
            val iv = bytes.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
            cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw ApiFailure("key_unavailable", "API Key 无法解密，请在设置中重新输入")
        }
    }

    @Synchronized fun save(key: String) {
        require(key.isNotBlank() && key.length <= 1024 && !key.any { it.isWhitespace() }) { "请输入有效的 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(1 + 12 + ciphertext.size).put(1).put(cipher.iv).put(ciphertext).array()
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }

    @Synchronized fun clear() { file.delete() }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
