package ai.opencode.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Only manage signing keys created for this MCP, never arbitrary entries in the app keystore. */
internal object DeviceKeys {
    private const val prefix = "opencode.device-mcp.signing."
    private val aliases = Regex("[A-Za-z0-9_-]{1,64}")

    @Synchronized fun run(a: JSONObject, approve: (JSONObject) -> Unit): JSONObject {
        val operation = a.getString("operation")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (operation == "list") {
            val keys = store.aliases().toList().filter { it.startsWith(prefix) }.sorted().map { alias ->
                JSONObject().put("alias", alias.removePrefix(prefix)).put("algorithm", store.getCertificate(alias).publicKey.algorithm)
            }
            return JSONObject().put("keys", JSONArray(keys))
        }
        val name = a.getString("alias"); require(aliases.matches(name)) { "Use a 1–64 character alphanumeric, underscore or hyphen alias" }
        val alias = prefix + name
        val summary = JSONObject().put("operation", operation).put("alias", name)
        when (operation) {
            "generate" -> {
                check(!store.containsAlias(alias)) { "Alias already exists" }
                val algorithm = a.optString("algorithm", "RSA")
                require(algorithm == "EC" || algorithm == "RSA") { "Expected EC or RSA" }
                val bits = a.optInt("bits", if (algorithm == "EC") 256 else 2048)
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                if (algorithm == "RSA") {
                    require(bits in setOf(2048, 3072, 4096)); spec.setKeySize(bits).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                } else {
                    val curve = mapOf(256 to "secp256r1", 384 to "secp384r1", 521 to "secp521r1")[bits] ?: error("Unsupported EC key size")
                    spec.setAlgorithmParameterSpec(ECGenParameterSpec(curve))
                }
                if (a.has("validitySeconds")) {
                    val seconds = a.getInt("validitySeconds"); require(seconds in 1..86400)
                    spec.setUserAuthenticationRequired(true).setUserAuthenticationParameters(seconds,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
                    summary.put("validitySeconds", seconds)
                }
                approve(summary.put("algorithm", algorithm).put("bits", bits))
                val pair = KeyPairGenerator.getInstance(algorithm, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
                return JSONObject().put("alias", name).put("publicKeySpki", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            }
            "delete" -> {
                check(store.containsAlias(alias)) { "Unknown key alias" }; approve(summary); store.deleteEntry(alias)
                return JSONObject().put("deleted", true)
            }
            "sign", "verify" -> {
                val public = store.getCertificate(alias)?.publicKey ?: error("Unknown key alias")
                val digest = a.optString("digest", "SHA256"); require(digest in setOf("SHA256", "SHA384", "SHA512"))
                val algorithm = digest + if (public.algorithm == "RSA") "withRSA" else "withECDSA"
                val bytes = Base64.decode(a.getString("dataBase64"), Base64.DEFAULT); require(bytes.size <= 32768)
                val engine = Signature.getInstance(algorithm)
                if (operation == "verify") {
                    engine.initVerify(public); engine.update(bytes)
                    return JSONObject().put("valid", engine.verify(Base64.decode(a.getString("signature"), Base64.DEFAULT)))
                }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                approve(summary.put("algorithm", algorithm).put("bytes", bytes.size).put("sha256", sha))
                engine.initSign(store.getKey(alias, null) as PrivateKey); engine.update(bytes)
                return JSONObject().put("algorithm", algorithm).put("signature", Base64.encodeToString(engine.sign(), Base64.NO_WRAP))
            }
            else -> error("Unknown keystore operation")
        }
    }
}
