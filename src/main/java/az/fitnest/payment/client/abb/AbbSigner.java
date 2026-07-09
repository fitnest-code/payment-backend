package az.fitnest.payment.client.abb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * ABB (Azericard) E-Commerce Gateway üçün kriptoqrafik imzalama komponenti.
 *
 * <h2>İmzalama Qaydası (spec §2.2 əsasında)</h2>
 * <p>MAC mənbə sətri: hər sahənin dəyəri ASCII-də onluq sahə uzunluğu ilə
 * prefiks olunur. Sahə boşdursa, onun yerinə {@code '-'} simvolu yazılır
 * (uzunluq 1 olaraq hesablanır).</p>
 * <pre>
 *   Məsələn:  AMOUNT="11.48", TERMINAL="99999999"
 *             → "511.48" + "899999999"
 *   Boş sahə: APPROVAL="" → "1-"
 * </pre>
 *
 * <h2>Alqoritm</h2>
 * <ul>
 *   <li>İmzalama: SHA256withRSA + Merchant şəxsi açarı (PKCS#8, min 2048-bit)</li>
 *   <li>Nəticə: onaltılıq (lowercase hex) sətir</li>
 *   <li>Yoxlama: SHA256withRSA + Azericard açıq açarı (X.509)</li>
 * </ul>
 */
@Component
public class AbbSigner {

    private static final Logger log = LoggerFactory.getLogger(AbbSigner.class);
    private static final String EMPTY_FIELD_PLACEHOLDER = "-";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";

    // ──────────────────────────────────────────────────────────────────────────
    // MAC mənbə sətri formatlama
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Bir sahəni MAC sətrinə əlavə edir.
     * Boş/null dəyərlər üçün {@code "1-"} yazılır (spec §MAC-qayda).
     *
     * @param value sahə dəyəri (null ola bilər)
     * @return formatlanmış string: {@code "<uzunluq><dəyər>"}
     */
    public String formatField(String value) {
        if (value == null || value.isEmpty()) {
            return "1" + EMPTY_FIELD_PLACEHOLDER;
        }
        return value.length() + value;
    }

    /**
     * Sıralı sahə dəyərləri massivindən MAC mənbə sətri qurur.
     * Spec-ə əsasən sahə sıralaması TRTYPE-dan asılıdır.
     *
     * @param fields TRTYPE-a uyğun sırada sahə dəyərləri (null ola bilər)
     * @return MAC mənbə sətri
     */
    public String buildMacSource(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            sb.append(formatField(field));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // İmzalama
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * MAC mənbə sətirini merchant-ın PKCS#8 şəxsi açarı ilə SHA256withRSA alqoritmi
     * ilə imzalayır.
     *
     * @param macSource  MAC mənbə sətri (spec §2.2.1-dən)
     * @param privateKeyBase64 Base64 kodlaşdırılmış PKCS#8 şəxsi açar (PEM başlıqları olmadan)
     * @return lowercase hex formatında imza (P_SIGN dəyəri)
     * @throws RuntimeException kriptoqrafik xəta baş verdikdə
     */
    public String sign(String macSource, String privateKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(stripPemHeaders(privateKeyBase64));
            PrivateKey privateKey = getPrivateKeyFromBytes(keyBytes);

            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(macSource.getBytes(StandardCharsets.US_ASCII));
            byte[] signatureBytes = signature.sign();

            String hexSignature = HexFormat.of().formatHex(signatureBytes);
            log.debug("[AbbSigner] Signed MAC source ({} chars) → P_SIGN ({} chars)", macSource.length(), hexSignature.length());
            return hexSignature;

        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            log.error("[AbbSigner] Signature generation failed", e);
            throw new RuntimeException("error.abb_signature_generation_failed", e);
        } catch (Exception e) {
            log.error("[AbbSigner] Unexpected error during signing", e);
            throw new RuntimeException("error.abb_crypto_error", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Yoxlama
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Azericard-dan gələn callback-in P_SIGN imzasını Azericard-ın açıq açarı
     * (AZERICARDpublic.pem) ilə yoxlayır.
     *
     * <p>Spec §P_SIGN yoxlaması: MAC mənbə sətri sahələri AMOUNT, TERMINAL,
     * APPROVAL, RRN, INT_REF ardıcıllığında yığılır.</p>
     *
     * @param macSource          MAC mənbə sətri (callback sahələrindən qurulmuş)
     * @param hexSignature       Azericard-dan gələn hex P_SIGN dəyəri
     * @param publicKeyBase64    Base64 kodlaşdırılmış X.509 açıq açar (PEM başlıqları olmadan)
     * @return {@code true} imza etibarlıdırsa, {@code false} deyilsə
     */
    public boolean verify(String macSource, String hexSignature, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(stripPemHeaders(publicKeyBase64));
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            byte[] signatureBytes = HexFormat.of().parseHex(hexSignature.toLowerCase());

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(macSource.getBytes(StandardCharsets.US_ASCII));

            boolean valid = sig.verify(signatureBytes);
            log.debug("[AbbSigner] Signature verification result: {}", valid);
            return valid;

        } catch (Exception e) {
            log.error("[AbbSigner] Signature verification failed with exception", e);
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Nonce generasiyası
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Spec tələbinə uyğun 16-32 bayt təsadüfi nonce yaradır (hex format).
     * Azericard spesifikasiyası: 8-32 random bayt, hexadecimal formatda.
     *
     * @return lowercase hex sətiri (32 simvol = 16 bayt)
     */
    public String generateNonce() {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return HexFormat.of().formatHex(nonce);
    }

    /**
     * Spec tələbinə uyğun timestamp yaradır.
     * Format: YYYYMMDDHHMMSS (GMT+0)
     *
     * @return 14 simvollu timestamp sətiri
     */
    public String generateTimestamp() {
        return java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Köməkçi metodlar
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * PEM başlıq/altlıq sətrlərini siliр: {@code -----BEGIN/END ...-----}
     * və boşluqları/yeni sətirləri kənarasdırır.
     *
     * @param pemOrRaw PEM formatı və ya xam Base64 sətiri
     * @return təmizlənmiş Base64 sətiri
     */
    private String stripPemHeaders(String pemOrRaw) {
        return pemOrRaw
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s+", "");
    }

    /**
     * Base64-dən oxunmuş açar baytlarını əvvəlcə PKCS#8, alınmadıqda isə PKCS#1 formatında parses edir.
     */
    private PrivateKey getPrivateKeyFromBytes(byte[] keyBytes) throws Exception {
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            log.warn("[AbbSigner] Key loading as PKCS#8 failed. Trying PKCS#1 fallback...");
            try {
                byte[] pkcs8Bytes = wrapPkcs1ToPkcs8(keyBytes);
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
                KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
                return keyFactory.generatePrivate(keySpec);
            } catch (Exception ex) {
                log.error("[AbbSigner] Failed to load key as both PKCS#8 and PKCS#1");
                throw e; // throw the original PKCS#8 parsing exception
            }
        }
    }

    /**
     * PKCS#1 formatındakı RSA private key baytlarını dinamik olaraq PKCS#8 formatına bükür (wrap).
     */
    private byte[] wrapPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        // AlgorithmIdentifier SEQUENCE for RSA (1.2.840.113549.1.1.1) and NULL parameters
        byte[] algId = new byte[] {
            0x30, 0x0d,
            0x06, 0x09,
            0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };

        byte[] octetString = encodeAsn1Length(0x04, pkcs1Bytes);

        byte[] inner = new byte[3 + algId.length + octetString.length];
        inner[0] = 0x02; // INTEGER
        inner[1] = 0x01; // Length
        inner[2] = 0x00; // Version 0

        System.arraycopy(algId, 0, inner, 3, algId.length);
        System.arraycopy(octetString, 0, inner, 3 + algId.length, octetString.length);

        return encodeAsn1Length(0x30, inner);
    }

    /**
     * ASN.1 formatına uyğun tag və uzunluq baytlarını əlavə edir.
     */
    private byte[] encodeAsn1Length(int tag, byte[] content) {
        int length = content.length;
        byte[] header;
        if (length < 128) {
            header = new byte[] { (byte) tag, (byte) length };
        } else if (length < 256) {
            header = new byte[] { (byte) tag, (byte) 0x81, (byte) length };
        } else if (length < 65536) {
            header = new byte[] { (byte) tag, (byte) 0x82, (byte) (length >> 8), (byte) (length & 0xff) };
        } else {
            header = new byte[] {
                (byte) tag, (byte) 0x83,
                (byte) (length >> 16), (byte) ((length >> 8) & 0xff), (byte) (length & 0xff)
            };
        }
        byte[] result = new byte[header.length + content.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(content, 0, result, header.length, content.length);
        return result;
    }
}
