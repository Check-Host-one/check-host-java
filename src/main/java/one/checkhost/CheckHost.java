package one.checkhost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Java client for the <a href="https://check-host.one">check-host.one</a> API:
 * ping, TCP ping, HTTP, traceroute and DNS checks from servers in 100+ countries.
 * No API key is needed.
 */
public final class CheckHost {
    public static final String VERSION = "1.0.0";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** An error response from the API. {@code retryAfter} (seconds) is set on 429, else -1. */
    public static final class CheckHostException extends IOException {
        public final int status;
        public final String type;
        public final long retryAfter;

        CheckHostException(int status, String type, String message, long retryAfter) {
            super(status + " " + type + ": " + message);
            this.status = status;
            this.type = type;
            this.retryAfter = retryAfter;
        }
    }

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CheckHost() { this("https://check-host.one"); }

    public CheckHost(String baseUrl) { this.baseUrl = baseUrl.replaceAll("/$", ""); }

    private JsonNode send(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "check-host-java/" + VERSION)
                .method(method, pub)
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode data = res.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(res.body());
        if (res.statusCode() >= 300) {
            long retry = res.headers().firstValue("Retry-After").map(Long::parseLong).orElse(-1L);
            throw new CheckHostException(res.statusCode(), data.path("error").path("type").asText("http_error"),
                    data.path("error").path("message").asText(""), retry);
        }
        return data;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** POST /api/v1/measurements. Returns {@code {"id", "probesCount"}}. */
    public JsonNode createMeasurement(Object request) throws IOException, InterruptedException {
        return send("POST", "/api/v1/measurements", request);
    }

    public JsonNode getMeasurement(String id) throws IOException, InterruptedException {
        return send("GET", "/api/v1/measurements/" + enc(id), null);
    }

    /** A saved result, kept for 90 days, also at https://check-host.one/r/{slug}. */
    public JsonNode getResult(String slug) throws IOException, InterruptedException {
        return send("GET", "/api/v1/results/" + enc(slug), null);
    }

    /** ASN, operator and location. A null target looks up the caller's own address. */
    public JsonNode hostInfo(String target) throws IOException, InterruptedException {
        return send("GET", "/api/v1/hostinfo" + (target == null ? "" : "?target=" + enc(target)), null);
    }

    public JsonNode probes() throws IOException, InterruptedException { return send("GET", "/api/v1/probes", null); }

    public JsonNode stats() throws IOException, InterruptedException { return send("GET", "/api/v1/stats", null); }

    /**
     * Start a check and poll once a second until it finishes (max ~60 s).
     * Locations are free-form: country code, city, continent, ASN or network name.
     */
    public JsonNode run(String type, String target, int limit, List<String> locations, Map<String, ?> options)
            throws IOException, InterruptedException {
        ObjectNode req = JSON.createObjectNode().put("type", type).put("target", target).put("limit", limit);
        if (locations != null && !locations.isEmpty()) {
            ArrayNode locs = req.putArray("locations");
            locations.forEach(l -> locs.addObject().put("magic", l));
        }
        if (options != null) req.set("measurementOptions", JSON.valueToTree(options));
        String id = createMeasurement(req).path("id").asText();
        JsonNode m = getMeasurement(id);
        for (int i = 0; i < 60 && "in-progress".equals(m.path("status").asText()); i++) {
            Thread.sleep(1000);
            m = getMeasurement(id);
        }
        return m;
    }

    public JsonNode ping(String target, int limit, String... locations) throws IOException, InterruptedException {
        return run("ping", target, limit, List.of(locations), Map.of("packets", 3));
    }

    public JsonNode tcpPing(String target, int port, int limit, String... locations) throws IOException, InterruptedException {
        return run("ping", target, limit, List.of(locations), Map.of("protocol", "TCP", "port", port));
    }

    public JsonNode http(String target, int limit, String... locations) throws IOException, InterruptedException {
        return run("http", target, limit, List.of(locations),
                Map.of("protocol", "HTTPS", "request", Map.of("method", "HEAD", "path", "/")));
    }

    public JsonNode traceroute(String target, int limit, String... locations) throws IOException, InterruptedException {
        return run("traceroute", target, limit, List.of(locations), Map.of("protocol", "ICMP"));
    }

    public JsonNode dns(String target, String record, int limit, String... locations) throws IOException, InterruptedException {
        return run("dns", target, limit, List.of(locations), Map.of("query", Map.of("type", record)));
    }
}
