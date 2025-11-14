package io.narayana.lra.testcontainers.tests;

import io.narayana.lra.testcontainers.CoordinatorClusterManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LraClusterIT {

    private CoordinatorClusterManager cluster;

    @BeforeAll
    void setUp() {
        cluster = new CoordinatorClusterManager();
        int count = Integer.parseInt(System.getProperty("lra.coordinator.count", "3"));
        cluster.startCoordinators(count);
    }

    @Test
    void testCoordinatorThroughProxy() throws Exception {
        String baseUrl = System.getProperty("lra.proxy.url");
        Assertions.assertNotNull("lra.proxy.url must be set by cluster manager", baseUrl);

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, response.statusCode());
    }
}
