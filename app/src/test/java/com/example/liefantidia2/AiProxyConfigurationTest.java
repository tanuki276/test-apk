package com.example.liefantidia2;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiProxyConfigurationTest {
    @Test public void distributedBuildHasAUsableEndpoint() {
        assertTrue("APK must contain a usable AI endpoint", new AiProxyClient().isConfigured());
    }
    @Test public void rejectsSampleOrUnsafeEndpoints() {
        for (String url : new String[] { null, "", "https://example.workers.dev/generate-recipe",
                "http://example.org/generate-recipe", "https://example.org/health",
                "https://user:password@example.org/generate-recipe",
                "https://example.org/generate-recipe?key=secret", "https://example.org/generate-recipe#fragment" }) {
            assertFalse(new AiProxyClient(url).isConfigured());
        }
    }
    @Test public void acceptsExplicitStagingEndpoint() {
        assertTrue(new AiProxyClient("https://staging.example.org/generate-recipe").isConfigured());
    }
}
