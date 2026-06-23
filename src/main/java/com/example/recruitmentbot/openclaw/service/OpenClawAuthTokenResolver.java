package com.example.recruitmentbot.openclaw.service;

import com.example.recruitmentbot.config.OpenClawProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OpenClawAuthTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(OpenClawAuthTokenResolver.class);

    private final OpenClawProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String cachedToken;

    public OpenClawAuthTokenResolver(OpenClawProperties properties) {
        this.properties = properties;
    }

    public String resolveToken() {
        if (StringUtils.hasText(properties.gatewayToken())) {
            return properties.gatewayToken().trim();
        }
        if (StringUtils.hasText(cachedToken)) {
            return cachedToken;
        }

        synchronized (this) {
            if (StringUtils.hasText(cachedToken)) {
                return cachedToken;
            }

            String resolved = resolveFromWsl();
            if (!StringUtils.hasText(resolved)) {
                resolved = resolveFromWindowsHome();
            }
            if (StringUtils.hasText(resolved)) {
                cachedToken = resolved.trim();
            }
            return cachedToken;
        }
    }

    private String resolveFromWindowsHome() {
        Path path = Path.of(System.getProperty("user.home"), ".openclaw", "openclaw.json");
        return readTokenFromPath(path, "Windows home");
    }

    private String resolveFromWsl() {
        String distro = StringUtils.hasText(properties.wslDistro()) ? properties.wslDistro().trim() : "Ubuntu-24.04";
        String tokenFromFilesystem = resolveFromWslFilesystem(distro);
        if (StringUtils.hasText(tokenFromFilesystem)) {
            return tokenFromFilesystem;
        }

        return resolveFromWslCommand(distro);
    }

    private String resolveFromWslFilesystem(String distro) {
        Path homeRoot = Path.of("\\\\wsl.localhost\\" + distro + "\\home");
        if (!Files.isDirectory(homeRoot)) {
            return null;
        }

        String currentUser = System.getProperty("user.name");
        if (StringUtils.hasText(currentUser)) {
            String token = readTokenFromPath(
                    homeRoot.resolve(currentUser).resolve(".openclaw").resolve("openclaw.json"),
                    "WSL filesystem distro=" + distro + ", user=" + currentUser
            );
            if (StringUtils.hasText(token)) {
                return token;
            }
        }

        try (var userDirectories = Files.list(homeRoot)) {
            for (Path userDirectory : userDirectories.filter(Files::isDirectory).toList()) {
                String token = readTokenFromPath(
                        userDirectory.resolve(".openclaw").resolve("openclaw.json"),
                        "WSL filesystem distro=" + distro + ", user=" + userDirectory.getFileName()
                );
                if (StringUtils.hasText(token)) {
                    return token;
                }
            }
        } catch (IOException exception) {
            log.warn("Failed to scan WSL filesystem for OpenClaw gateway token. distro={}", distro, exception);
        }
        return null;
    }

    private String resolveFromWslCommand(String distro) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "wsl.exe",
                    "-d",
                    distro,
                    "--",
                    "sh",
                    "-lc",
                    "cat ~/.openclaw/openclaw.json"
            ).redirectErrorStream(true).start();

            boolean finished = process.waitFor(Math.max(properties.tokenResolveTimeoutSeconds(), 15), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Timed out while resolving OpenClaw gateway token from WSL distro={}", distro);
                return null;
            }

            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (StringUtils.hasText(output)) {
                    log.warn("Could not read OpenClaw gateway token from WSL distro={}. output={}", distro, output);
                }
                return null;
            }

            String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String token = extractToken(json);
            if (StringUtils.hasText(token)) {
                log.info("Resolved OpenClaw gateway token from WSL command distro={}", distro);
            }
            return token;
        } catch (IOException exception) {
            log.warn("Failed to start wsl.exe while resolving OpenClaw gateway token", exception);
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while resolving OpenClaw gateway token from WSL");
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String readTokenFromPath(Path path, String sourceLabel) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            String token = extractToken(Files.readString(path));
            if (StringUtils.hasText(token)) {
                log.info("Resolved OpenClaw gateway token from {}", sourceLabel);
            }
            return token;
        } catch (IOException exception) {
            log.warn("Failed to read OpenClaw gateway token from path={}", path, exception);
            return null;
        }
    }

    private String extractToken(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String token = root.path("gateway").path("auth").path("token").asText(null);
            return StringUtils.hasText(token) ? token.trim() : null;
        } catch (IOException exception) {
            log.warn("Failed to parse OpenClaw config while extracting gateway token", exception);
            return null;
        }
    }
}
