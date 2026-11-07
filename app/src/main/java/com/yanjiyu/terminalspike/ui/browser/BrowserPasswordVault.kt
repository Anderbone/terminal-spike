package com.yanjiyu.terminalspike.ui.browser

import android.content.Context
import android.content.SharedPreferences
import com.yanjiyu.terminalspike.core.security.credential.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Reuses the app's authenticated encryption, with a dedicated device-bound browser key. */
internal class BrowserPasswordVault(context: Context) {
    private val records = BrowserPasswordRecords(context.getSharedPreferences("browser-passwords", Context.MODE_PRIVATE))
    private val store = AesGcmCredentialStore(records,
        AndroidKeystoreCredentialKeyProvider("terminal_spike_browser_password_aes_256_gcm_v2"))

    suspend fun read(target: BrowserLoginTarget): String? = withContext(Dispatchers.IO) {
        val reference = reference(target)
        if (records.read(reference.secretId) == null) return@withContext null
        store.withSecret(reference) { it.toString(Charsets.UTF_8) }
    }

    suspend fun save(target: BrowserLoginTarget, password: String) = withContext(Dispatchers.IO) {
        store.saveAndWipe(reference(target), password.toByteArray(Charsets.UTF_8))
    }

    suspend fun forget(target: BrowserLoginTarget) = withContext(Dispatchers.IO) {
        store.delete(reference(target).secretId)
    }

    private fun reference(target: BrowserLoginTarget): CredentialSecretReference {
        fun id(purpose: String) = UUID.nameUUIDFromBytes(("browser-password\u0000$purpose\u0000" + target.scope).toByteArray(Charsets.UTF_8))
        return CredentialSecretReference(CredentialId.from(id("credential")), SecretId.from(id("secret")), CredentialSecretKind.PASSWORD)
    }
}

private class BrowserPasswordRecords(private val preferences: SharedPreferences) : CredentialCiphertextStore {
    override suspend fun read(secretId: SecretId): EncryptedCredentialRecord? {
        val json = preferences.getString(secretId.value, null)?.let(::JSONObject) ?: return null
        return EncryptedCredentialRecord(json.getString("id"), json.getString("kind"),
            json.getInt("envelopeVersion"), json.getInt("keyVersion"),
            Base64.getDecoder().decode(json.getString("nonce")), Base64.getDecoder().decode(json.getString("ciphertext")))
    }

    override suspend fun write(record: EncryptedCredentialRecord) {
        val json = JSONObject().put("id", record.secretId).put("kind", record.kindCode)
            .put("envelopeVersion", record.envelopeVersion).put("keyVersion", record.keyVersion)
            .put("nonce", Base64.getEncoder().encodeToString(record.copyNonce()))
            .put("ciphertext", Base64.getEncoder().encodeToString(record.copyCiphertext()))
        check(preferences.edit().putString(record.secretId, json.toString()).commit()) { "Could not save the browser password." }
    }

    override suspend fun delete(secretId: SecretId): Boolean {
        val existed = preferences.contains(secretId.value)
        check(preferences.edit().remove(secretId.value).commit()) { "Could not remove the browser password." }
        return existed
    }
}
