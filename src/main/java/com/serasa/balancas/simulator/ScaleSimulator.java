package com.serasa.balancas.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone simulator for an ESP32-based scale. Sets up a truck and an open
 * transport transaction, then fires POST requests to a running instance of
 * the application every 100ms with decreasing noise, exercising the full
 * ingestion -> stabilization -> persistence -> idempotency pipeline over
 * real HTTP, ending in a completed weighing. Not a Spring bean; run as a
 * plain Java main class against an already-running app (e.g. started via
 * ./mvnw spring-boot:run).
 */
public final class ScaleSimulator {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String SCALE_ID = "BAL-001";
    private static final String API_KEY = "key-sorriso-001";
    private static final long GRAIN_TYPE_ID = 1L;
    private static final long BRANCH_ID = 1L;
    private static final double TRUCK_TARE_KG = 8500.0;
    private static final double TARGET_WEIGHT_KG = 32000.0;
    private static final long TOTAL_DURATION_MS = 10_000;

    private static final double INITIAL_NOISE_KG = 50.0;
    private static final double MIN_NOISE_KG = 2.0;
    private static final long DECAY_MS = 5_000;
    private static final long TICK_INTERVAL_MS = 100;

    private static final int FINAL_STATUS_MAX_ATTEMPTS = 10;
    private static final long FINAL_STATUS_POLL_INTERVAL_MS = 300;

    private ScaleSimulator() {
    }

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ObjectMapper objectMapper = new ObjectMapper();

        String plate = "SIM" + (System.currentTimeMillis() % 100_000);
        JsonNode truck = createTruck(client, objectMapper, plate);
        System.out.printf("created truck id=%s plate=%s tare=%.1f%n", truck.get("id"), plate, TRUCK_TARE_KG);

        JsonNode transaction = openTransaction(client, objectMapper, truck.get("id").asLong());
        long transactionId = transaction.get("id").asLong();
        System.out.printf("opened transaction id=%d status=%s%n", transactionId, transaction.get("status").asText());

        // Seeded from the current timestamp (not 1) so seq does not collide with a prior
        // run's already-claimed scaleId:seq keys in ReadingIdempotencyService's process-
        // lifetime map -- a fixed starting seq would make every readings after the first
        // run silently dedupe against the same SCALE_ID.
        long seq = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();
        long elapsedMs;

        do {
            elapsedMs = System.currentTimeMillis() - startTime;
            double noise = Math.max(MIN_NOISE_KG, INITIAL_NOISE_KG * Math.max(0, 1 - (double) elapsedMs / DECAY_MS));
            double weight = TARGET_WEIGHT_KG + ThreadLocalRandom.current().nextDouble(-noise, noise);

            sendReading(client, objectMapper, plate, seq, weight);
            seq++;

            Thread.sleep(TICK_INTERVAL_MS);
        } while (elapsedMs < TOTAL_DURATION_MS);

        JsonNode finalTransaction = awaitCompletedTransaction(client, objectMapper, transactionId);
        System.out.printf(
                "final transaction id=%d status=%s netWeightKg=%s loadCost=%s%n",
                transactionId,
                finalTransaction.get("status").asText(),
                finalTransaction.get("netWeightKg"),
                finalTransaction.get("loadCost"));

        System.out.println("done");
    }

    private static JsonNode createTruck(HttpClient client, ObjectMapper objectMapper, String plate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("licensePlate", plate);
        body.put("tare", TRUCK_TARE_KG);
        return postJson(client, objectMapper, "/api/trucks", body);
    }

    private static JsonNode openTransaction(HttpClient client, ObjectMapper objectMapper, long truckId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("truckId", truckId);
        body.put("grainTypeId", GRAIN_TYPE_ID);
        body.put("branchId", BRANCH_ID);
        return postJson(client, objectMapper, "/api/transactions", body);
    }

    /**
     * Polls the transaction after the reading loop ends, since persistence happens
     * asynchronously relative to the last POST and may not have landed yet. Retries a
     * bounded number of times before returning whatever the last fetch produced.
     */
    private static JsonNode awaitCompletedTransaction(HttpClient client, ObjectMapper objectMapper, long transactionId)
            throws Exception {
        JsonNode transaction = fetchTransaction(client, objectMapper, transactionId);
        for (int attempt = 1; attempt < FINAL_STATUS_MAX_ATTEMPTS && !"COMPLETED".equals(transaction.get("status").asText());
                attempt++) {
            Thread.sleep(FINAL_STATUS_POLL_INTERVAL_MS);
            transaction = fetchTransaction(client, objectMapper, transactionId);
        }
        return transaction;
    }

    private static JsonNode fetchTransaction(HttpClient client, ObjectMapper objectMapper, long transactionId)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/transactions/" + transactionId))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private static JsonNode postJson(HttpClient client, ObjectMapper objectMapper, String path, Map<String, Object> body)
            throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("POST " + path + " failed with status " + response.statusCode()
                    + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private static void sendReading(HttpClient client, ObjectMapper objectMapper, String plate, long seq, double weight)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", SCALE_ID);
        body.put("plate", plate);
        body.put("weight", weight);
        body.put("seq", seq);

        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/scales/readings"))
                .header("Content-Type", "application/json")
                .header("X-Scale-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        System.out.printf("seq=%d weight=%.1f status=%d%n", seq, weight, response.statusCode());
    }
}
