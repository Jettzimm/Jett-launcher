package com.jett.launcher

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The "use it anywhere" link between the phone and the box goes through a public relay
 * (ntfy.sh), so everything on it is sealed: compressed, then AES-256-GCM with a key only the
 * box and Jett's phone hold. The relay sees random-looking text; anything not sealed with
 * the key fails to open and is ignored. Same format in the phone app (WebCrypto).
 *
 *   sealed  = "v1." + base64url(iv[12] + ciphertext+tag)      (plaintext = raw-deflated UTF-8)
 *   reply   = "R.<id>.<i>.<n>.<piece>"  (a sealed reply cut into pieces that fit one message)
 *
 * Pure Kotlin/Java (no Android), so it's unit-tested on the computer.
 */
object RelayCrypto {
    private val rnd = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    const val PIECE = 3800   // ntfy.sh keeps a message as text up to 4,096 bytes

    fun newKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }
    fun newTopic(): String {
        val abc = "abcdefghijkmnopqrstuvwxyz23456789"
        return "jett-" + (1..24).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
    }
    fun enc(b: ByteArray): String = b64.encodeToString(b)
    fun dec(s: String): ByteArray? = try { unb64.decode(s) } catch (e: Exception) { null }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun inflate(b: ByteArray, max: Int = 1 shl 20): ByteArray {
        val i = Inflater(true)
        i.setInput(b + 0)   // raw inflate wants one spare byte at the end
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        try {
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) break
                out.write(buf, 0, n)
                require(out.size() <= max) { "too big" }
            }
        } finally { i.end() }
        return out.toByteArray()
    }

    fun seal(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "v1." + enc(iv + c.doFinal(deflate(plain.toByteArray(Charsets.UTF_8))))
    }

    /** The plaintext, or null if it isn't ours (wrong key, tampered, garbage). */
    fun open(key: ByteArray, sealed: String): String? = try {
        require(sealed.startsWith("v1."))
        val raw = dec(sealed.substring(3))!!
        require(raw.size > 12 + 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        String(inflate(c.doFinal(raw, 12, raw.size - 12)), Charsets.UTF_8)
    } catch (e: Exception) { null }

    /** A sealed reply cut into relay messages. */
    fun pieces(id: String, sealed: String): List<String> {
        val parts = sealed.chunked(PIECE)
        return parts.mapIndexed { i, p -> "R.$id.$i.${parts.size}.$p" }
    }
}
