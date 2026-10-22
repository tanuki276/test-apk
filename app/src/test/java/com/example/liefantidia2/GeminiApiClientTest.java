package com.example.liefantidia2;

import org.json.JSONException;
import org.junit.Test;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GeminiApiClientTest {
    private static final String SUCCESS = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"卵\"},{\"text\":\"焼き\"}]}}]}";

    @Test public void combinesTextParts() throws Exception {
        assertEquals("卵焼き", GeminiApiClient.parseRecipeFromResponse(SUCCESS));
    }
    @Test(expected = JSONException.class) public void rejectsEmptyCandidates() throws Exception {
        GeminiApiClient.parseRecipeFromResponse("{\"candidates\":[]}");
    }
    @Test(expected = JSONException.class) public void rejectsTruncatedRecipe() throws Exception {
        GeminiApiClient.parseRecipeFromResponse(SUCCESS.replace("STOP", "MAX_TOKENS"));
    }
    @Test(expected = JSONException.class) public void rejectsBlockedRecipe() throws Exception {
        GeminiApiClient.parseRecipeFromResponse("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
    }
    @Test public void omitsThoughtParts() throws Exception {
        assertEquals("卵焼き", GeminiApiClient.parseRecipeFromResponse(SUCCESS.replace("[{\"text\":", "[{\"thought\":true,\"text\":\"private\"},{\"text\":")));
    }
    @Test public void successCompletesOnceAndKeepsKeyOutOfUrl() throws Exception {
        verifyRequest(200, SUCCESS, 1, 0);
    }
    @Test public void httpFailureNeverCompletesOrReturnsRecipe() throws Exception {
        verifyRequest(429, "{}", 0, 1);
    }
    @Test public void invalidJsonNeverCompletesOrReturnsRecipe() throws Exception {
        verifyRequest(200, "not json", 0, 1);
    }
    private void verifyRequest(int code, String body, int success, int failure) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(code).setBody(body));
            server.start();
            AtomicInteger results = new AtomicInteger();
            AtomicInteger completions = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            OkHttpClient http = new OkHttpClient();
            GeminiApiClient client = new GeminiApiClient(http, server.url("/generate").toString());
            try {
                client.generateRecipe("test-key", "卵", "2人分", new GeminiApiClient.RecipeCallback() {
                    public void onResult(String recipe) { results.incrementAndGet(); }
                    public void onComplete() { completions.incrementAndGet(); done.countDown(); }
                    public void onFailure(String error) { failures.incrementAndGet(); done.countDown(); }
                });
                assertTrue(done.await(5, TimeUnit.SECONDS));
                // Wait for the entire callback, including any accidental second terminal event.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (http.dispatcher().runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                assertEquals(0, http.dispatcher().runningCallsCount());
                assertEquals(success, results.get());
                assertEquals(success, completions.get());
                assertEquals(failure, failures.get());
                RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("/generate", request.getPath());
                assertEquals("test-key", request.getHeader("x-goog-api-key"));
            } finally {
                client.cancel();
                http.dispatcher().executorService().shutdownNow();
                http.connectionPool().evictAll();
            }
        }
    }
}
