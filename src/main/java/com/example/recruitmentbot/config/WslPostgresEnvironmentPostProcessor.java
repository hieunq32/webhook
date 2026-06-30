package com.example.recruitmentbot.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

public class WslPostgresEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Pattern LOCAL_POSTGRES_PATTERN =
            Pattern.compile("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1):(\\d+)(/.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(2);
    private static final String PROPERTY_SOURCE_NAME = "wslPostgresDiscovery";

    private final org.apache.commons.logging.Log log;

    public WslPostgresEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(getClass());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!isWindows()) {
            return;
        }
        if (!Arrays.asList(environment.getActiveProfiles()).contains("postgres")) {
            return;
        }
        if (StringUtils.hasText(System.getenv("POSTGRES_URL"))) {
            return;
        }

        String datasourceUrl = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(datasourceUrl)) {
            return;
        }

        Matcher matcher = LOCAL_POSTGRES_PATTERN.matcher(datasourceUrl);
        if (!matcher.matches()) {
            return;
        }

        int port = Integer.parseInt(matcher.group(2));
        if (canConnect("127.0.0.1", port)) {
            return;
        }

        Optional<String> wslIp = resolveWslIp(environment);
        if (wslIp.isEmpty()) {
            log.warn("PostgreSQL localhost fallback failed: unable to resolve WSL distro IP");
            return;
        }
        if (!canConnect(wslIp.get(), port)) {
            log.warn("PostgreSQL localhost fallback failed: WSL IP " + wslIp.get() + ":" + port + " is unreachable");
            return;
        }

        String resolvedUrl = "jdbc:postgresql://" + wslIp.get() + ":" + port + matcher.group(3);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("spring.datasource.url", resolvedUrl);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
        log.info("Resolved PostgreSQL datasource URL to WSL host " + wslIp.get() + ":" + port);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private Optional<String> resolveWslIp(ConfigurableEnvironment environment) {
        String distro = environment.getProperty("wsl.postgres.distro", "Ubuntu-24.04");
        ProcessBuilder builder = new ProcessBuilder(
                "wsl",
                "-d",
                distro,
                "--",
                "bash",
                "-lc",
                "hostname -I"
        );
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.readLine();
                if (process.waitFor() != 0 || !StringUtils.hasText(output)) {
                    return Optional.empty();
                }
                Matcher matcher = IPV4_PATTERN.matcher(output);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
                return Optional.empty();
            }
        } catch (Exception ex) {
            log.warn("Failed to query WSL distro IP for PostgreSQL fallback: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) SOCKET_TIMEOUT.toMillis());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
