package com.fx.csvtest.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

/**
 * Read-only CLI browser for RabbitMQ queues via the Management HTTP API.
 * Requires the rabbitmq_management plugin to be enabled (default in the Docker image).
 *
 * Usage: java RabbitQueueInspector [--host localhost] [--port 15672]
 *                                   [--user guest] [--password guest]
 */
public final class RabbitQueueInspector {

    private static final List<String> DEFAULT_QUEUES = List.of(
            "fx.pacs009.inbound",
            "fx.payment.valid",
            "fx.payment.invalid");

    private static final int BODY_PREVIEW_LIMIT = 1200;
    private static final int DEFAULT_PEEK_LIMIT = 10;

    private final String baseUrl;
    private final String authHeader;
    private final List<String> queues;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private RabbitQueueInspector(String host, int port, String user, String password, List<String> queues) {
        this.baseUrl = "http://" + host + ":" + port + "/api";
        String credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + credentials;
        this.queues = queues;
    }

    public static void main(String[] args) throws Exception {
        Options opts = Options.parse(args);
        if (opts.help) {
            printUsage();
            return;
        }
        new RabbitQueueInspector(opts.host, opts.port, opts.user, opts.password, opts.queues).run();
    }

    private void run() {
        System.out.println();
        System.out.println("Connected to RabbitMQ Management API: " + baseUrl);
        System.out.println("Mode: read-only peek (messages are re-queued after inspection).");

        try (Scanner scanner = new Scanner(System.in)) {
            boolean running = true;
            while (running) {
                printMenu();
                String choice = prompt(scanner, "Select");
                switch (choice) {
                    case "1" -> printSummary();
                    case "2" -> browseQueue(scanner);
                    case "3" -> peekMessage(scanner);
                    case "4" -> running = false;
                    default  -> System.out.println("Unknown option: " + choice);
                }
            }
        }
    }

    private void printMenu() {
        System.out.println();
        System.out.println("==== RabbitMQ Queue Inspector ====");
        System.out.println("1. Queue summary");
        System.out.println("2. Browse queue messages");
        System.out.println("3. Peek one message body");
        System.out.println("4. Quit");
    }

    private void printSummary() {
        System.out.println();
        System.out.printf("%-4s %-28s %-8s%n", "#", "Queue", "Messages");
        System.out.println("-".repeat(44));
        for (int i = 0; i < queues.size(); i++) {
            String q = queues.get(i);
            System.out.printf("%-4d %-28s %-8d%n", i + 1, q, countMessages(q));
        }
        System.out.println("Checked at " + LocalDateTime.now());
    }

    private void browseQueue(Scanner scanner) {
        String queueName = chooseQueue(scanner);
        if (queueName == null) return;

        int limit = readLimit(scanner);
        List<String> bodies = peekMessages(queueName, limit);

        if (bodies.isEmpty()) {
            System.out.println("Queue is empty: " + queueName);
            return;
        }
        for (int i = 0; i < bodies.size(); i++) {
            System.out.println();
            System.out.println("#" + (i + 1));
            String body = bodies.get(i);
            System.out.println(body.length() <= BODY_PREVIEW_LIMIT
                    ? body
                    : body.substring(0, BODY_PREVIEW_LIMIT) + "\n... truncated, use peek for full body ...");
        }
    }

    private void peekMessage(Scanner scanner) {
        String queueName = chooseQueue(scanner);
        if (queueName == null) return;

        int requested = readPositiveInt(prompt(scanner, "Message number"), 1);
        List<String> bodies = peekMessages(queueName, requested);

        if (requested > bodies.size()) {
            System.out.println("No message #" + requested + " on " + queueName + ". Current count: " + bodies.size());
            return;
        }
        System.out.println();
        System.out.println("#" + requested);
        System.out.println("Body:");
        System.out.println(bodies.get(requested - 1));
    }

    // ── Management API calls ───────────────────────────────────────────────

    private long countMessages(String queue) {
        try {
            String url = baseUrl + "/queues/%2F/" + encode(queue);
            JsonNode root = get(url);
            return root.path("messages").asLong(0);
        } catch (Exception e) {
            return -1;
        }
    }

    private List<String> peekMessages(String queue, int count) {
        List<String> bodies = new ArrayList<>();
        try {
            String url = baseUrl + "/queues/%2F/" + encode(queue) + "/get";
            String body = "{\"count\":" + count + ",\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\",\"truncate\":50000}";
            JsonNode root = post(url, body);
            if (root.isArray()) {
                for (JsonNode msg : root) {
                    bodies.add(msg.path("payload").asText(""));
                }
            }
        } catch (Exception e) {
            System.out.println("Error peeking queue: " + e.getMessage());
        }
        return bodies;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    private JsonNode post(String url, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    private static String encode(String queue) {
        return queue.replace(".", "%2E");
    }

    // ── CLI helpers ────────────────────────────────────────────────────────

    private String chooseQueue(Scanner scanner) {
        System.out.println();
        for (int i = 0; i < queues.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, queues.get(i));
        }
        String selected = prompt(scanner, "Queue number");
        int index = readPositiveInt(selected, -1);
        if (index < 1 || index > queues.size()) {
            System.out.println("Invalid queue number: " + selected);
            return null;
        }
        return queues.get(index - 1);
    }

    private int readLimit(Scanner scanner) {
        String raw = prompt(scanner, "Max messages [" + DEFAULT_PEEK_LIMIT + "]");
        return raw.isBlank() ? DEFAULT_PEEK_LIMIT : readPositiveInt(raw, DEFAULT_PEEK_LIMIT);
    }

    private String prompt(Scanner scanner, String label) {
        System.out.print(label + "> ");
        return scanner.nextLine().trim();
    }

    private int readPositiveInt(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: RabbitQueueInspector [options]

                Options:
                  --host     localhost   (default)
                  --port     15672       (RabbitMQ Management API port)
                  --user     guest       (default)
                  --password guest       (default)
                  --queues   q1,q2,q3   (default: fx queues)
                  --help
                """);
    }

    // ── Options ────────────────────────────────────────────────────────────

    private static final class Options {
        String host     = env("RABBITMQ_HOST",     "localhost");
        int    port     = Integer.parseInt(env("RABBITMQ_MGMT_PORT", "15672"));
        String user     = env("RABBITMQ_USER",     "guest");
        String password = env("RABBITMQ_PASSWORD", "guest");
        List<String> queues = parseQueues(env("RABBITMQ_QUEUES", String.join(",", DEFAULT_QUEUES)));
        boolean help;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> o.help = true;
                    case "--host"       -> o.host     = args[++i];
                    case "--port"       -> o.port     = Integer.parseInt(args[++i]);
                    case "--user"       -> o.user     = args[++i];
                    case "--password"   -> o.password = args[++i];
                    case "--queues"     -> o.queues   = parseQueues(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return o;
        }

        private static String env(String name, String fallback) {
            String v = System.getenv(name);
            return (v == null || v.isBlank()) ? fallback : v;
        }

        private static List<String> parseQueues(String raw) {
            List<String> list = new ArrayList<>();
            for (String q : raw.split(",")) {
                if (!q.isBlank()) list.add(q.trim());
            }
            return list.isEmpty() ? DEFAULT_QUEUES : list;
        }
    }
}
