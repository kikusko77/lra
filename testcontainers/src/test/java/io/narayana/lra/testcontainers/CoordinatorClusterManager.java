package io.narayana.lra.testcontainers;

import org.testcontainers.containers.Network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CoordinatorClusterManager {

    private final Network network = Network.newNetwork();
    private final List<LraCoordinatorContainer> coordinators = new ArrayList<>();
    private ProxyContainer proxy;

    public void startCoordinators(int count) {
        // Optional: start registry HTTP server (for your own monitoring / Debezium later)
        try {
            RegistryHttpServer.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start registry HTTP server", e);
        }

        for (int i = 1; i <= count; i++) {
            startCoordinator("coord-" + i);
        }

        proxy = new ProxyContainer(network);
        proxy.start();

        // This is the URL your tests / MP config should use
        System.setProperty("lra.proxy.url", proxy.getUrl() + "/lra-coordinator");
        // or if MP LRA expects lra.coordinator.url:
        System.setProperty("lra.coordinator.url", proxy.getUrl() + "/lra-coordinator");
    }

    private void startCoordinator(String id) {
        LraCoordinatorContainer c = new LraCoordinatorContainer(id)
                .withNetwork(network)
                .withNetworkAliases(id);

        c.start();

        // Registry DB is for you (e.g. to track instances, Debezium, etc.)
        RegistryDatabase.insert(id, id, 8080); // internal port, not mapped one
        coordinators.add(c);
    }

    public String getProxyUrl() {
        return proxy.getUrl() + "/lra-coordinator";
    }
}
