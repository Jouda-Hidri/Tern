package tern.workflow

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object WebhookSignature {

    fun sign(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun verify(secret: String, body: String, header: String?): Boolean {
        if (secret.isBlank()) return true
        if (header.isNullOrBlank()) return false
        val expected = sign(secret, body).toByteArray()
        return header.split(',', ' ')
            .map { it.trim().substringAfter('=', it.trim()) }
            .filter { it.isNotBlank() }
            .any { MessageDigest.isEqual(expected, it.lowercase().toByteArray()) }
    }
}
