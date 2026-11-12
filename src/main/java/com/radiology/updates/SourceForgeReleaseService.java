package com.radiology.updates;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

public final class SourceForgeReleaseService {
    private static final URI RELEASE_FEED = URI.create(
            "https://sourceforge.net/projects/eliterads-pro-platform/best_release.json");
    private static final DateTimeFormatter SOURCEFORGE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final HttpClient httpClient;

    public SourceForgeReleaseService() {
        this(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build());
    }

    SourceForgeReleaseService(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient);
    }

    public WindowsRelease checkForWindowsRelease() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(RELEASE_FEED)
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "EliteradsPro-UpdateChecker")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("SourceForge returned HTTP " + response.statusCode());
        }
        return parseWindowsRelease(response.body());
    }

    static WindowsRelease parseWindowsRelease(String responseBody) throws IOException {
        try {
            JSONObject release = new JSONObject(responseBody)
                    .getJSONObject("platform_releases")
                    .getJSONObject("windows");
            String filename = release.getString("filename");
            String checksum = release.getString("sha256sum");
            String modified = release.getString("date_modified");
            String normalizedPath = normalizeReleasePath(filename);
            if (!checksum.matches("(?i)[0-9a-f]{64}")) {
                throw new IOException("The Windows release has no valid SHA-256 checksum.");
            }
            LocalDateTime modifiedAt;
            try {
                modifiedAt = LocalDateTime.parse(modified, SOURCEFORGE_DATE);
            } catch (DateTimeParseException e) {
                throw new IOException("The Windows release date is invalid.", e);
            }
            return new WindowsRelease(
                    normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1),
                    modifiedAt,
                    checksum.toLowerCase(java.util.Locale.ROOT),
                    createDownloadUri(normalizedPath));
        } catch (JSONException e) {
            throw new IOException("SourceForge returned an incomplete Windows release record.", e);
        }
    }

    private static String normalizeReleasePath(String filename) throws IOException {
        if (!filename.startsWith("/") || filename.startsWith("//")) {
            throw new IOException("SourceForge returned an invalid Windows release path.");
        }
        String[] segments = filename.substring(1).split("/");
        if (segments.length == 0) {
            throw new IOException("SourceForge returned an empty Windows release path.");
        }
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("SourceForge returned an unsafe Windows release path.");
            }
        }
        return "/" + String.join("/", segments);
    }

    private static URI createDownloadUri(String releasePath) {
        String encodedPath = java.util.Arrays.stream(releasePath.substring(1).split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        return URI.create("https://sourceforge.net/projects/eliterads-pro-platform/files/"
                + encodedPath + "/download");
    }

    public record WindowsRelease(String filename, LocalDateTime modifiedAt, String sha256, URI downloadUri) {
        public WindowsRelease {
            Objects.requireNonNull(filename);
            Objects.requireNonNull(modifiedAt);
            Objects.requireNonNull(sha256);
            Objects.requireNonNull(downloadUri);
        }

        public String formattedModifiedAt() {
            return modifiedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}
