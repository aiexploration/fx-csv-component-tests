package com.fx.csvtest.tools;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;

/**
 * Small read-only command-line JMS browser for the SwiftPay Artemis queues.
 */
public final class ArtemisQueueInspector {

    private static final List<String> DEFAULT_QUEUES = List.of(
            "fx.pacs009.inbound",
            "fx.payment.valid",
            "fx.payment.invalid");

    private static final int DEFAULT_PEEK_LIMIT = 10;
    private static final int BODY_PREVIEW_LIMIT = 1200;

    private final String brokerUrl;
    private final String user;
    private final String password;
    private final List<String> queues;

    private ArtemisQueueInspector(String brokerUrl, String user, String password, List<String> queues) {
        this.brokerUrl = brokerUrl;
        this.user = user;
        this.password = password;
        this.queues = queues;
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            printUsage();
            return;
        }

        ArtemisQueueInspector inspector = new ArtemisQueueInspector(
                options.brokerUrl,
                options.user,
                options.password,
                options.queues);
        inspector.run();
    }

    private void run() throws JMSException {
        try (ActiveMQConnectionFactory cf = connectionFactory();
             Connection connection = createConnection(cf);
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             Scanner scanner = new Scanner(System.in)) {

            connection.start();
            System.out.println();
            System.out.println("Connected to Artemis: " + brokerUrl);
            System.out.println("Mode: read-only browse. Messages are not consumed.");

            boolean running = true;
            while (running) {
                printMenu();
                String choice = prompt(scanner, "Select");
                switch (choice) {
                    case "1" -> printSummary(session);
                    case "2" -> browseQueue(session, scanner);
                    case "3" -> peekMessage(session, scanner);
                    case "4" -> running = false;
                    default -> System.out.println("Unknown option: " + choice);
                }
            }
        }
    }

    private ActiveMQConnectionFactory connectionFactory() {
        if (isBlank(user)) {
            return new ActiveMQConnectionFactory(brokerUrl);
        }
        return new ActiveMQConnectionFactory(brokerUrl, user, password == null ? "" : password);
    }

    private Connection createConnection(ActiveMQConnectionFactory cf) throws JMSException {
        if (isBlank(user)) {
            return cf.createConnection();
        }
        return cf.createConnection(user, password == null ? "" : password);
    }

    private void printMenu() {
        System.out.println();
        System.out.println("==== Artemis Queue Inspector ====");
        System.out.println("1. Queue summary");
        System.out.println("2. Browse queue messages");
        System.out.println("3. Peek one message body");
        System.out.println("4. Quit");
    }

    private void printSummary(Session session) throws JMSException {
        System.out.println();
        System.out.printf("%-4s %-28s %-8s%n", "#", "Queue", "Messages");
        System.out.println("-".repeat(44));
        for (int i = 0; i < queues.size(); i++) {
            String queueName = queues.get(i);
            System.out.printf("%-4d %-28s %-8d%n", i + 1, queueName, count(session, queueName));
        }
        System.out.println("Checked at " + LocalDateTime.now());
    }

    private void browseQueue(Session session, Scanner scanner) throws JMSException {
        String queueName = chooseQueue(scanner);
        if (queueName == null) {
            return;
        }

        int limit = readLimit(scanner);
        Queue queue = session.createQueue(queueName);
        try (QueueBrowser browser = session.createBrowser(queue)) {
            Enumeration<?> messages = browser.getEnumeration();
            int index = 0;
            while (messages.hasMoreElements() && index < limit) {
                Message message = (Message) messages.nextElement();
                index++;
                System.out.println();
                printMessageHeader(index, message);
                System.out.println("Body preview:");
                System.out.println(previewBody(message));
            }
            if (index == 0) {
                System.out.println("Queue is empty: " + queueName);
            }
        }
    }

    private void peekMessage(Session session, Scanner scanner) throws JMSException {
        String queueName = chooseQueue(scanner);
        if (queueName == null) {
            return;
        }

        int requested = readPositiveInt(prompt(scanner, "Message number"), 1);
        Queue queue = session.createQueue(queueName);
        try (QueueBrowser browser = session.createBrowser(queue)) {
            Enumeration<?> messages = browser.getEnumeration();
            int index = 0;
            while (messages.hasMoreElements()) {
                Message message = (Message) messages.nextElement();
                index++;
                if (index == requested) {
                    printMessageHeader(index, message);
                    System.out.println("Body:");
                    System.out.println(messageBody(message));
                    return;
                }
            }
            System.out.println("No message #" + requested + " on " + queueName + ". Current count: " + index);
        }
    }

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

    private int count(Session session, String queueName) throws JMSException {
        Queue queue = session.createQueue(queueName);
        int count = 0;
        try (QueueBrowser browser = session.createBrowser(queue)) {
            Enumeration<?> messages = browser.getEnumeration();
            while (messages.hasMoreElements()) {
                messages.nextElement();
                count++;
            }
        }
        return count;
    }

    private int readLimit(Scanner scanner) {
        String raw = prompt(scanner, "Max messages [" + DEFAULT_PEEK_LIMIT + "]");
        if (isBlank(raw)) {
            return DEFAULT_PEEK_LIMIT;
        }
        return readPositiveInt(raw, DEFAULT_PEEK_LIMIT);
    }

    private void printMessageHeader(int index, Message message) throws JMSException {
        System.out.println("#" + index);
        System.out.println("  JMSMessageID   : " + message.getJMSMessageID());
        System.out.println("  JMSCorrelation : " + message.getJMSCorrelationID());
        System.out.println("  JMSTimestamp   : " + message.getJMSTimestamp());
        System.out.println("  Type           : " + message.getClass().getSimpleName());
    }

    private String previewBody(Message message) throws JMSException {
        String body = messageBody(message);
        if (body.length() <= BODY_PREVIEW_LIMIT) {
            return body;
        }
        return body.substring(0, BODY_PREVIEW_LIMIT) + "\n... truncated, use peek for full body ...";
    }

    private String messageBody(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        return "<non-text message: " + message.getClass().getName() + ">";
    }

    private String prompt(Scanner scanner, String label) {
        System.out.print(label + "> ");
        return scanner.nextLine().trim();
    }

    private int readPositiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void printUsage() {
        System.out.println("""
                Usage: ArtemisQueueInspector [options]

                Options:
                  --broker-url tcp://localhost:61616
                  --user artemis
                  --password artemis
                  --queues q1,q2,q3
                  --help

                Environment defaults:
                  JMS_BROKER_URL, JMS_USER, JMS_PASSWORD, JMS_QUEUES
                """);
    }

    private static final class Options {
        private String brokerUrl = envOrDefault("JMS_BROKER_URL", "tcp://localhost:61616");
        private String user = envOrDefault("JMS_USER", "");
        private String password = envOrDefault("JMS_PASSWORD", "");
        private List<String> queues = parseQueues(envOrDefault("JMS_QUEUES", String.join(",", DEFAULT_QUEUES)));
        private boolean help;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> options.help = true;
                    case "--broker-url" -> options.brokerUrl = requiredValue(args, ++i, arg);
                    case "--user" -> options.user = requiredValue(args, ++i, arg);
                    case "--password" -> options.password = requiredValue(args, ++i, arg);
                    case "--queues" -> options.queues = parseQueues(requiredValue(args, ++i, arg));
                    default -> {
                        if (arg.startsWith("--broker-url=")) {
                            options.brokerUrl = arg.substring("--broker-url=".length());
                        } else if (arg.startsWith("--user=")) {
                            options.user = arg.substring("--user=".length());
                        } else if (arg.startsWith("--password=")) {
                            options.password = arg.substring("--password=".length());
                        } else if (arg.startsWith("--queues=")) {
                            options.queues = parseQueues(arg.substring("--queues=".length()));
                        } else {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                    }
                }
            }
            return options;
        }

        private static String requiredValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static String envOrDefault(String name, String fallback) {
            String value = System.getenv(name);
            return isBlank(value) ? fallback : value;
        }

        private static List<String> parseQueues(String raw) {
            List<String> parsed = new ArrayList<>();
            for (String queue : raw.split(",")) {
                if (!queue.isBlank()) {
                    parsed.add(queue.trim());
                }
            }
            return parsed.isEmpty() ? DEFAULT_QUEUES : parsed;
        }
    }
}
