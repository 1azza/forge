package forge.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Opaque capability tokens that prove a client owns a LAN seat.
 *
 * <p>The raw token is handed to the client exactly once (on claim) and is otherwise only
 * ever presented back over HTTP/WebSocket. We never store the raw value: the room holds a
 * SHA-256 digest, so a memory or log dump of the room can't be replayed as an identity.
 */
public final class CapabilityTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private CapabilityTokens() { }

    /** A fresh 256-bit random token, base64url encoded. */
    public static String issue() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A deterministic digest of a token, suitable for storage. */
    public static String hash(final String token) {
        if (token == null) {
            return null;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of a presented token against a stored hash. */
    public static boolean matches(final String token, final String storedHash) {
        if (token == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
