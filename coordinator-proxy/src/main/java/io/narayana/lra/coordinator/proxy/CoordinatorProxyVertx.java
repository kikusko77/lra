package io.narayana.lra.coordinator.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.client.WebClient;
import java.io.Closeable;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 *
 * <p>
 * How it works:
 * <ol>
 * <li>Every incoming request goes into a FIFO queue.
 * <li>A dispatcher thread picks requests one by one and sends them to the
 * next coordinator in the healthy queue using the Vert.x {@link WebClient}.
 * <li>If a coordinator fails it moves to the crashed queue. A background
 * timer checks crashed coordinators via {@code /q/health/ready} and moves
 * them back when they respond with HTTP 200.
 * <li>If a request waits longer than {@value #REQUEST_TIMEOUT_MS} ms it gets a 503.
 * </ol>
 */
public class CoordinatorProxyVertx implements Closeable {

    private static final Logger LOG = Logger.getLogger(CoordinatorProxyVertx.class);

    private static final int HEALTH_CHECK_MS = 2_000;
    private static final int FORWARD_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 60_000;
    private static final int POLL_MS = 200;

    private final List<URI> backends;
    private final int port;
    private volatile int actualPort;

    private final Vertx vertx;
    private final WebClient webClient;
    private final HttpServer server;
    private Thread dispatcher;

    /**
     * Incoming requests waiting to be forwarded, in arrival order.
     */
    private final BlockingDeque<PendingRequest> queue = new LinkedBlockingDeque<>();

    /**
     * Coordinators ready to handle requests (index into {@link #backends}).
     */
    private final BlockingDeque<Integer> healthy = new LinkedBlockingDeque<>();

    /**
     * Coordinators that failed -- polled via {@code /q/health/ready} until they recover.
     */
    private final Queue<Integer> crashed = new ConcurrentLinkedQueue<>();

    /**
     * One request sitting in the queue. The {@code done} flag is flipped to
     * {@code true} exactly once -- either when a response is written or when the
     * request times out -- so we never write to the same exchange twice.
     */
    private record PendingRequest(HttpServerRequest request, Buffer body, AtomicBoolean done) {
        PendingRequest(HttpServerRequest request, Buffer body) {
            this(request, body, new AtomicBoolean(false));
        }
    }

    /**
     * @param port local port to listen on
     * @param backends real coordinator base URIs, e.g. {@code http://localhost:<backend-port>/lra-coordinator}
     */
    public CoordinatorProxyVertx(int port, List<URI> backends) {
        this.port = port;
        this.actualPort = port;
        this.backends = List.copyOf(backends);
        this.vertx = Vertx.vertx();
        this.webClient = WebClient.create(vertx);
        this.server = vertx.createHttpServer().requestHandler(this::enqueue);

        for (int i = 0; i < backends.size(); i++) {
            healthy.add(i);
        }
    }

    /**
     * Starts the server, the dispatcher thread, and the health-check timer.
     */
    public void start() throws Exception {
        server.listen(port).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        actualPort = server.actualPort();

        dispatcher = new Thread(this::dispatchLoop, "lra-proxy-vertx-dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();

        vertx.setPeriodic(HEALTH_CHECK_MS, id -> checkCrashed());

        LOG.infof("CoordinatorProxyVertx started on :%d -> %s", actualPort, backends);
    }

    /**
     * The URL that LRA clients and tests should use.
     */
    public URI proxyCoordinatorUri() {
        return URI.create("http://127.0.0.1:" + actualPort + "/lra-coordinator");
    }

    /**
     * Rebuilds the proxy's backend view from the coordinators that are ready now,
     * then restores the healthy queue to the natural backend order.
     */
    public void resetRoutingOrder(List<Integer> readyIndexes) {
        healthy.clear();
        crashed.clear();

        Set<Integer> ready = new HashSet<>(readyIndexes);
        for (int i = 0; i < backends.size(); i++) {
            if (ready.contains(i)) {
                healthy.addLast(i);
            } else {
                crashed.add(i);
            }
        }
        LOG.infof("Reset proxy routing order: healthy=%s, crashed=%s", healthy, crashed);
    }

    public Integer peekNextHealthyIndex() {
        return healthy.peekFirst();
    }

    @Override
    public void close() {
        if (dispatcher != null)
            dispatcher.interrupt();
        server.close();
        webClient.close();
        vertx.close();
    }

    /**
     * Reads the body and pushes the request into the FIFO queue.
     */
    private void enqueue(HttpServerRequest request) {
        request.body().onSuccess(body -> {
            PendingRequest req = new PendingRequest(request, body);
            queue.add(req);
            LOG.debugf("Queued %s %s (depth %d)", request.method(), request.uri(), queue.size());

            // Time out the request if it waits too long.
            vertx.setTimer(REQUEST_TIMEOUT_MS, id -> {
                if (req.done().compareAndSet(false, true)) {
                    queue.remove(req);
                    request.response().setStatusCode(503).end();
                    LOG.warnf("Timed out: %s %s", request.method(), request.uri());
                }
            });
        });
    }

    /**
     * Runs on a dedicated thread. Takes one request at a time from the front of
     * the queue and picks the next healthy coordinator. If no coordinator is
     * available the request goes back to the front and the thread waits briefly.
     */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            PendingRequest req;
            try {
                req = queue.take();
            } catch (InterruptedException e) {
                // Restore the flag (cleared by the throw) so anything that runs after
                // this method returns sees the correct interrupted state.
                Thread.currentThread().interrupt();
                return;
            }

            if (req.done().get())
                continue; // already timed out

            Integer idx;
            try {
                idx = healthy.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (idx == null) {
                queue.addFirst(req);
                continue;
            }

            forward(req, idx);
        }
    }

    /**
     * Sends the request to the given coordinator via the Vert.x WebClient.
     * Returns immediately -- success/failure callbacks run on the Vert.x event loop.
     */
    private void forward(PendingRequest req, int idx) {
        if (req.done().get()) {
            healthy.addLast(idx);
            return;
        }

        URI backend = backends.get(idx);
        String url = backend.getScheme() + "://" + backend.getHost() + ":" + backend.getPort()
                + req.request().uri();

        LOG.infof(">> [%d] %s %s", idx, req.request().method(), url);

        webClient.requestAbs(req.request().method(), url)
                .timeout(FORWARD_TIMEOUT_MS)
                .putHeaders(req.request().headers())
                .sendBuffer(req.body())
                .onSuccess(resp -> {
                    if (req.done().compareAndSet(false, true)) {
                        var r = req.request().response().setStatusCode(resp.statusCode());
                        resp.headers().forEach(e -> r.headers().add(e.getKey(), e.getValue()));
                        Buffer body = resp.body();
                        if (body != null && body.length() > 0)
                            r.end(body);
                        else
                            r.end();
                        LOG.infof("<< [%d] %d", idx, resp.statusCode());
                    }
                    healthy.addLast(idx);
                })
                .onFailure(err -> {
                    LOG.warnf("FAIL [%d] %s failed: %s", idx, backend, err.getMessage());
                    crashed.add(idx);
                    if (!req.done().get())
                        queue.addFirst(req);
                });
    }

    private void checkCrashed() {
        for (Integer idx : crashed) {
            URI base = backends.get(idx);
            String url = base.getScheme() + "://" + base.getHost() + ":" + base.getPort() + "/q/health/ready";

            webClient.getAbs(url).timeout(HEALTH_CHECK_MS).send()
                    .onSuccess(r -> {
                        if (r.statusCode() == 200 && crashed.remove(idx)) {
                            healthy.addLast(idx);
                            LOG.infof("UP [%d] %s recovered", idx, base);
                        }
                    });
        }
    }
}
