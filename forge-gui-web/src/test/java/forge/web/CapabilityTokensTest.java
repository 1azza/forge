package forge.web;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class CapabilityTokensTest {

    @Test
    public void issueProducesOpaqueUniqueTokens() {
        final String a = CapabilityTokens.issue();
        final String b = CapabilityTokens.issue();
        assertNotEquals(a, b, "tokens must be unique");
        assertFalse(a.isEmpty());
        assertFalse(a.contains("+"), "base64url has no padding characters");
        assertFalse(a.contains("/"));
    }

    @Test
    public void hashIsDeterministicAndDistinctFromToken() {
        final String token = CapabilityTokens.issue();
        assertNotEquals(token, CapabilityTokens.hash(token), "the stored value must not be the raw token");
        assertTrue(CapabilityTokens.hash(token).equals(CapabilityTokens.hash(token)), "hash must be deterministic");
    }

    @Test
    public void matchesValidatesConstantTime() {
        final String token = CapabilityTokens.issue();
        assertTrue(CapabilityTokens.matches(token, CapabilityTokens.hash(token)));
        assertFalse(CapabilityTokens.matches(CapabilityTokens.issue(), CapabilityTokens.hash(token)));
        assertFalse(CapabilityTokens.matches(null, CapabilityTokens.hash(token)));
        assertFalse(CapabilityTokens.matches(token, null));
        assertFalse(CapabilityTokens.matches(null, null));
    }
}
