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

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Append-only ledger of order actions (PLACE, REDUCE, CLOSE) used to power the
 * Order Log table on the Orders desk.
 *
 * <p>Each event is one JSON file under {@code <root>/order-events/}. Files are
 * written atomically and never mutated. This is intentionally separate from
 * {@link OptionOrderService}'s {@code ManualExecution} files, which represent
 * the running state of a position; this store is the audit trail of every
 * action that changed it.</p>
 */
public final class OrderEventStore {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Path root;
    private final Gson gson;

    public OrderEventStore(Path root) {
        this.root = root;
        this.gson = new GsonBuilder()
                .serializeNulls()
                .registerTypeAdapter(Instant.class, new InstantCodec())
                .create();
    }

    public OrderEvent record(OrderEvent event) {
        try {
            Files.createDirectories(root);
            String safeId = event.eventId().replaceAll("[^A-Za-z0-9._-]", "_");
            Path target = root.resolve(safeId + ".json");
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(event), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return event;
        } catch (IOException e) {
            // Audit-log failures must never break order placement / monitor reduce.
            // Surface to stderr so it shows up in dev logs but swallow otherwise.
            System.err.println("[OrderEventStore] failed to write event " + event.eventId() + ": " + e.getMessage());
            return event;
        }
    }

    public List<OrderEvent> loadToday() throws IOException {
        if (Files.notExists(root)) return List.of();
        LocalDate today = LocalDate.now(IST);
        List<OrderEvent> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            for (Path file : stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    OrderEvent ev = gson.fromJson(json, OrderEvent.class);
                    if (ev != null && ev.timestamp() != null
                            && ev.timestamp().atZone(IST).toLocalDate().equals(today)) {
                        out.add(ev);
                    }
                } catch (Exception ignored) {
                    // Skip corrupted rows — log stays usable.
                }
            }
        }
        out.sort(Comparator.comparing(OrderEvent::timestamp).reversed());
        return out;
    }

    /** Returns true when no event with the given dedupe key exists for today. */
    public boolean hasEventForExecution(String executionId, String type) throws IOException {
        if (executionId == null || executionId.isBlank()) return false;
        for (OrderEvent ev : loadToday()) {
            if (executionId.equals(ev.executionId()) && type.equals(ev.type())) return true;
        }
        return false;
    }

    public int purgeBefore(LocalDate tradingDay) throws IOException {
        if (Files.notExists(root)) return 0;
        int deleted = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            for (Path file : stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    OrderEvent ev = gson.fromJson(json, OrderEvent.class);
                    if (ev != null && ev.timestamp() != null
                            && ev.timestamp().atZone(IST).toLocalDate().isBefore(tradingDay)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (Exception ignored) {
                    // Leave unreadable files — operator can clean manually.
                }
            }
        }
        return deleted;
    }

    public int clearToday() throws IOException {
        if (Files.notExists(root)) return 0;
        LocalDate today = LocalDate.now(IST);
        int deleted = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(root)) {
            for (Path file : stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList()) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    OrderEvent ev = gson.fromJson(json, OrderEvent.class);
                    if (ev != null && ev.timestamp() != null
                            && ev.timestamp().atZone(IST).toLocalDate().equals(today)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return deleted;
    }

    public static String newId() {
        return "EVT-" + UUID.randomUUID();
    }

    /**
     * Single audit entry. Immutable after write.
     *
     * @param type    one of {@code "PLACE"}, {@code "REDUCE"}, {@code "CLOSE"}.
     * @param status  {@code "COMPLETED"} or {@code "REJECTED"}.
     * @param source  one of {@code "MANUAL"}, {@code "MONITOR"}, {@code "STRATEGY_LAB"}.
     */
    public record OrderEvent(
            String eventId,
            Instant timestamp,
            String type,
            String status,
            String source,
            String executionId,
            String strategyId,
            String underlying,
            String instrumentId,
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            String side,
            int lots,
            int lotSize,
            BigDecimal price,
            BigDecimal bookedPnlDelta,
            String message
    ) {
    }

    private static final class InstantCodec
            implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type type, JsonSerializationContext ctx) {
            return src == null ? null : new JsonPrimitive(src.toString());
        }
        @Override
        public Instant deserialize(JsonElement json, Type type, JsonDeserializationContext ctx)
                throws JsonParseException {
            if (json == null || json.isJsonNull()) return null;
            String v = json.getAsString();
            return v == null || v.isBlank() ? null : Instant.parse(v);
        }
    }
}
