package tw.edu.cse.nsysu.api.service;

import static org.junit.Assert.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.List;
import org.junit.Test;
import tw.edu.cse.nsysu.api.DefenseRoutes;

public class DefenseRoutesTest {
    @Test public void httpContractEnforcesAllChoicesAndPersistsReports() throws Exception {
        var file = Files.createTempFile("defense-http-", ".db");
        var csv = Files.createTempFile("defense-http-", ".csv");
        var client = HttpClient.newHttpClient();
        var mapper = new ObjectMapper();
        try (var training = DriverManager.getConnection("jdbc:sqlite::memory:");
             var store = new DefenseStore(file)) {
            try (var statement = training.createStatement()) {
                statement.execute("CREATE TABLE DataTable(raw_sql TEXT,split_group TEXT)");
                statement.execute("INSERT INTO DataTable VALUES('training fixture','train')");
            }
            Files.writeString(csv, "payload,label,split\nKeyboard,0,test\nblocked fixture,1,test\n");
            var predictor = new DefenseService.Predictor() {
                public String version() { return "http-fixture"; }
                public DefenseService.Prediction predict(String input, String architecture,
                        IncrementalAlgorithm algorithm) {
                    if ("10000".equals(input) || "review block".equals(input)) {
                        return new DefenseService.Prediction(0.1, 0.5, "review", List.of());
                    }
                    boolean blocked = input.contains("blocked");
                    double score = blocked ? 0.9 : 0.1;
                    return new DefenseService.Prediction(blocked ? 0.9 : 0.1, 0.5,
                            ModelStateService.defenseAction(architecture, score, 0.5, List.of()), List.of());
                }
            };
            var app = Javalin.create();
            DefenseRoutes.register(app, predictor, 2, training, csv, store);
            app.start("127.0.0.1", 0);
            try {
                String base = "http://127.0.0.1:" + app.port() + "/api/defense/";
                var status = client.send(HttpRequest.newBuilder(URI.create(base + "status")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, status.statusCode());
                assertEquals("ENFORCE", mapper.readTree(status.body()).get("mode").asText());
                for (String architecture : FrozenArchitectureComparisonService.ARCHITECTURES) {
                    var response = post(client, base + "search", "{\"keyword\":\"blocked fixture\","
                            + "\"architecture\":\"" + architecture + "\"}");
                    assertEquals(403, response.statusCode());
                    assertFalse(mapper.readTree(response.body()).get("businessQueryAttempted").asBoolean());
                }
                for (var algorithm : IncrementalAlgorithm.available()) {
                    var response = post(client, base + "search", "{\"keyword\":\"Keyboard\","
                            + "\"architecture\":\"EXTENDED_ARM\",\"algorithm\":\"" + algorithm.getId() + "\"}");
                    assertEquals(200, response.statusCode());
                    var result = mapper.readTree(response.body());
                    assertEquals("Keyboard", result.get("products").get(0).get("name").asText());
                    assertTrue(result.get("businessQueryCompleted").asBoolean());
                }
                var pendingResponse = post(client, base + "search", "{\"keyword\":\"10000\","
                        + "\"architecture\":\"EXTENDED_ARM\",\"algorithm\":\"SGD\"}");
                assertEquals(200, pendingResponse.statusCode());
                var pending = mapper.readTree(pendingResponse.body());
                assertEquals("review_pending", pending.get("status").asText());
                assertFalse(pending.get("businessQueryAttempted").asBoolean());
                var allowResponse = post(client, base + "review", "{\"requestId\":\""
                        + pending.get("requestId").asText() + "\",\"decision\":\"allow\"}");
                assertEquals(200, allowResponse.statusCode());
                var allowed = mapper.readTree(allowResponse.body());
                assertEquals("Notebook", allowed.get("products").get(0).get("name").asText());
                assertTrue(allowed.get("businessQueryCompleted").asBoolean());

                var blockPending = mapper.readTree(post(client, base + "search", "{\"keyword\":\"review block\","
                        + "\"architecture\":\"EXTENDED_ARM\",\"algorithm\":\"SGD\"}").body());
                var blockResponse = post(client, base + "review", "{\"requestId\":\""
                        + blockPending.get("requestId").asText() + "\",\"decision\":\"block\"}");
                assertEquals(403, blockResponse.statusCode());
                var blockedReview = mapper.readTree(blockResponse.body());
                assertFalse(blockedReview.get("businessQueryAttempted").asBoolean());

                assertEquals(400, post(client, base + "search", "{broken").statusCode());
                assertEquals(400, post(client, base + "search", "{\"keyword\":\"Keyboard\"}").statusCode());
                assertEquals(400, post(client, base + "search",
                        "{\"keyword\":\"Keyboard\",\"architecture\":\"PURE_EXTENDED_ARM\",\"algorithm\":\"SGD\"}").statusCode());
                var comparison = post(client, base + "evaluation", "{\"extendedOnly\":false,\"sampleSize\":2}");
                assertEquals(200, comparison.statusCode());
                assertEquals(7, mapper.readTree(comparison.body()).get("results").size());
                for (var result : mapper.readTree(comparison.body()).get("results")) {
                    if ("PURE_EXTENDED_ARM".equals(result.get("architecture").asText())) {
                        assertEquals(0.0, result.get("fpr").asDouble(), 0);
                        assertEquals(1.0, result.get("benignDenialRate").asDouble(), 0);
                        assertEquals(1, result.get("reviewCount").asInt());
                    }
                }
                assertEquals(10, store.recent(200).size());
                assertEquals("complete", store.latestReport().get("status").asText());
            } finally { app.stop(); }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(csv);
        }
    }

    private HttpResponse<String> post(HttpClient client, String uri, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
