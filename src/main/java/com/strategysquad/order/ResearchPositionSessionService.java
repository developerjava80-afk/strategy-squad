package com.strategysquad.order;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.strategysquad.order.PositionSessionActionRequest;
import com.strategysquad.order.PositionSessionSnapshot;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * File-backed store for persisted scenario research position sessions.
 */
public final class ResearchPositionSessionService {
    private final Path storeRoot;
    private final Gson gson;

    public ResearchPositionSessionService(Path storeRoot) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot must not be null");
        this.gson = new GsonBuilder()
                .serializeNulls()
                .registerTypeAdapter(Instant.class, new InstantJsonCodec())
                .create();
    }

    public java.util.List<PositionSessionSnapshot> loadAll() throws IOException {
        if (Files.notExists(storeRoot)) {
            return java.util.List.of();
        }
        java.util.List<PositionSessionSnapshot> results = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(storeRoot)) {
            for (Path file : stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    PositionSessionSnapshot snapshot = gson.fromJson(json, PositionSessionSnapshot.class);
                    if (snapshot != null) results.add(snapshot);
                } catch (Exception ignored) {
                    // skip corrupted files
                }
            }
        }
        return java.util.List.copyOf(results);
    }

    public java.util.List<PositionSessionSnapshot> loadForTradingDay(LocalDate tradingDay, ZoneId zoneId)
            throws IOException {
        Objects.requireNonNull(tradingDay, "tradingDay must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        return loadAll().stream()
                .filter(s -> s.createdAt() != null)
                .filter(s -> s.createdAt().atZone(zoneId).toLocalDate().equals(tradingDay))
                .toList();
    }

    public int clearTodaysSessions(ZoneId zoneId) throws IOException {
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        if (Files.notExists(storeRoot)) return 0;
        LocalDate today = LocalDate.now(zoneId);
        int deleted = 0;
        for (PositionSessionSnapshot snapshot : loadAll()) {
            if (snapshot == null || snapshot.createdAt() == null) continue;
            if (snapshot.createdAt().atZone(zoneId).toLocalDate().equals(today)
                    && delete(snapshot.sessionId())) {
                deleted++;
            }
        }
        return deleted;
    }

    public int deleteSessionsBefore(LocalDate tradingDay, ZoneId zoneId) throws IOException {
        Objects.requireNonNull(tradingDay, "tradingDay must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        if (Files.notExists(storeRoot)) {
            return 0;
        }
        int deleted = 0;
        for (PositionSessionSnapshot snapshot : loadAll()) {
            if (snapshot == null || snapshot.createdAt() == null) {
                continue;
            }
            LocalDate sessionDay = snapshot.createdAt().atZone(zoneId).toLocalDate();
            if (sessionDay.isBefore(tradingDay) && delete(snapshot.sessionId())) {
                deleted++;
            }
        }
        return deleted;
    }

    public PositionSessionSnapshot load(String sessionId) throws IOException {
        Path file = fileForSession(sessionId);
        if (Files.notExists(file)) {
            return null;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return gson.fromJson(json, PositionSessionSnapshot.class);
    }

    public void save(PositionSessionSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        ensureStoreRoot();
        Path target = fileForSession(snapshot.sessionId());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, gson.toJson(snapshot), StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean delete(String sessionId) throws IOException {
        Path file = fileForSession(sessionId);
        return Files.deleteIfExists(file);
    }

    public PositionSessionSnapshot parseSession(String json) {
        return gson.fromJson(json, PositionSessionSnapshot.class);
    }

    public PositionSessionActionRequest parseAction(String json) {
        return gson.fromJson(json, PositionSessionActionRequest.class);
    }

    public String toJson(PositionSessionSnapshot snapshot) {
        return gson.toJson(snapshot);
    }

    private Path fileForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        String safeSessionId = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return storeRoot.resolve(safeSessionId + ".json");
    }

    private void ensureStoreRoot() throws IOException {
        Files.createDirectories(storeRoot);
    }

    private static final class InstantJsonCodec implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return src == null ? null : new JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String value = json.getAsString();
            return value == null || value.isBlank() ? null : Instant.parse(value);
        }
    }
}

