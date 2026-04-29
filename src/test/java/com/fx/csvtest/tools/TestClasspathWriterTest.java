package com.fx.csvtest.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TestClasspathWriterTest {

    @Test
    void writesSurefireClasspath() throws IOException {
        String outputFile = System.getProperty("queue.inspector.classpath.file", "target/test-classpath.txt");
        Path outputPath = Path.of(outputFile);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.writeString(outputPath, System.getProperty("java.class.path"));
    }
}
