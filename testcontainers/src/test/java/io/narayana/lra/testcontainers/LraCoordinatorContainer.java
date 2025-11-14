package io.narayana.lra.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class LraCoordinatorContainer extends GenericContainer<LraCoordinatorContainer> {

    public LraCoordinatorContainer(String id) {
        super("eclipse/narayana-lra-coordinator"); // or your custom image

        withExposedPorts(8080);
        waitingFor(Wait.forHttp("/lra-coordinator"));

        // Traefik labels for Docker provider
        // One router+service per coordinator instance
        String serviceName = "lra-coordinator-" + id;

        withLabel("traefik.enable", "true");
        withLabel("traefik.http.services." + serviceName + ".loadbalancer.server.port", "8080");
        withLabel("traefik.http.routers." + serviceName + ".rule", "PathPrefix(`/lra-coordinator`)");
        withLabel("traefik.http.routers." + serviceName + ".entrypoints", "http");
        withLabel("traefik.http.routers." + serviceName + ".service", serviceName);
    }
}
