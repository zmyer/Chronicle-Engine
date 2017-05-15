/*
 * Copyright 2016 higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.openhft.chronicle.engine.mit;

import org.jetbrains.annotations.NotNull;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class TestUtils {
    /**
     * Calculates the runtime in nanoseconds from the given start time in nanoseconds.
     * Prints the runtime in nanos, millis and seconds.
     *
     * @param startTimeInNanoseconds Start time in nanos.
     * @return Runtime in nanos.
     */
    public static long calculateAndPrintRuntime(long startTimeInNanoseconds) {
        return calculateAndPrintRuntime(startTimeInNanoseconds, 1);
    }

    public static long calculateAndPrintRuntime(long startTimeInNanoseconds, int count) {
        long endNanoTime = System.nanoTime();
        return printRuntime(endNanoTime - startTimeInNanoseconds, count);
    }

    public static long printRuntime(long runtimeNanoSeconds, int count) {
        double runtimeMilliseconds = (double) runtimeNanoSeconds / 1000000.0;

        double runtimeSeconds = runtimeMilliseconds / 1000.0;

        System.out.printf("For %,d tests, Runtime: %,d nanoseconds | %.1f milliseconds | %.3f seconds%s%n",
                count, runtimeNanoSeconds / count, runtimeMilliseconds / count, runtimeSeconds / count, count > 1 ? " average" : "");

        return runtimeNanoSeconds / count;
    }

    /**
     * Run the test code the configured number of times and verify that the average runtime is less than or equal
     * to the given max runtime.
     *
     * @param testToRun         Test code to run configured number of times.
     * @param noOfRuns          Runs.
     * @param maxRuntimeInNanos Max runtime.
     */
    public static void runMultipleTimesAndVerifyAvgRuntime(@NotNull Runnable testToRun, int noOfRuns, long maxRuntimeInNanos) {
        runMultipleTimesAndVerifyAvgRuntime(x -> {
        }, testToRun, noOfRuns, maxRuntimeInNanos);
    }

    public static void runMultipleTimesAndVerifyAvgRuntime(@NotNull IntConsumer setup, @NotNull Runnable testToRun, int noOfRuns, long maxRuntimeInNanos) {
        @NotNull AtomicLong totalTime = new AtomicLong();

        // one warmup.
        IntStream.range(0, noOfRuns).forEach(e -> {
            setup.accept(e);
            long start = System.nanoTime();
            testToRun.run();
            long delta = System.nanoTime() - start;
//            System.out.println(delta/1e9+" secs");
            totalTime.addAndGet(delta);
        });

        long runtimeInNanos = printRuntime(totalTime.get(), noOfRuns);

        Assert.assertTrue(runtimeInNanos + " > " + maxRuntimeInNanos, runtimeInNanos <= maxRuntimeInNanos);
    }

    /**
     * Loads the given file into a string.
     *
     * @param fileName Name of the resource to load.
     * @return String value of the text file.
     * @throws IOException
     */
    public static String loadSystemResourceFileToString(String fileName) throws IOException, URISyntaxException {
        URL testFileUrl = ClassLoader.getSystemResource(fileName);
        URI testFileUri = testFileUrl.toURI();

        @NotNull final StringBuilder stringBuilder = new StringBuilder();
        Files.lines(Paths.get(testFileUri)).forEach(stringBuilder::append);

        return stringBuilder.toString();
    }

    /**
     * Loads the given system resource and assumes it is a csv file with a key and value column. These key/value pairs
     * are loaded into a map.
     *
     * @param resourcePath Path to resource file that is to be loaded into a map.
     * @return Map loaded with key/value pairs from the resource file.
     * @throws IOException
     * @throws URISyntaxException
     */
    @NotNull
    public static Map<String, Double> loadSystemResourceKeyValueCsvFileToMap(String resourcePath) throws IOException, URISyntaxException {
        URL testFileUrl = ClassLoader.getSystemResource(resourcePath);
        URI testFileUri = testFileUrl.toURI();

        @NotNull Map<String, Double> results = new HashMap<>();

        Files.lines(Paths.get(testFileUri)).forEach(x -> {
            @NotNull String[] strings = x.split(",");

            results.put(strings[0], Double.parseDouble(strings[1]));
        });

        return results;
    }

    /**
     * @param extension
     * @param stringToWrite
     * @throws IOException
     */
    public static void saveTestFileToDisk(String extension, @NotNull String stringToWrite) throws IOException {
        Files.write(Paths.get("./test" + extension), stringToWrite.getBytes(ISO_8859_1));
    }

    public static void deleteTestFile(String extension) throws IOException {
        deleteFile(Paths.get("./test" + extension).toString());
    }

    public static void deleteFile(@NotNull String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (Exception e) {
            System.err.println(String.format("Failed to delete file '%s'. Exception: %s", path, e));
        }
    }

    public static void createDirectoryIfNotExists(@NotNull String directoryName) {
        @NotNull File directory = new File(directoryName);

        if (!directory.exists()) {

            System.out.println("Creating directory: " + directoryName);

            boolean result = false;

            try {
                directory.mkdir();
                result = true;
            } catch (SecurityException se) {
                System.out.println("Could not create directory '%s'.");
            }

            if (result) {
                System.out.println("DIR created");
            }
        }
    }

    /**
     * @param mapName Name of map
     * @param counter Counter to be used for key
     * @return Generated value based on map name and counter
     */
    public static String getValue(String mapName, int counter) {
        return String.format("Val-%s-%s", mapName, counter);
    }

    /**
     * @param mapName Name of map
     * @param counter Counter to be used for key
     * @return Generated key based on map name and counter
     */
    public static String getKey(String mapName, int counter) {
        return String.format("%s-%s", mapName, counter);
    }
}