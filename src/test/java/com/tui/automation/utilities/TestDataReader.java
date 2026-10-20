package com.tui.automation.utilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads JSON files from src/test/resources/testdata into Java records. */
public final class TestDataReader {

    // Records fail fast on missing or null fields; unknown fields (typos) already fail by default.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    private TestDataReader() {
    }

    public static <T> T read(String fileName, Class<T> type) {
        String resource = "testdata/" + fileName;
        try (InputStream in = TestDataReader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Test data file not found on classpath: " + resource);
            }
            return MAPPER.readValue(in, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid test data in " + resource + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + resource, e);
        }
    }
}
