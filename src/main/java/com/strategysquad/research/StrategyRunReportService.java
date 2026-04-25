package com.strategysquad.research;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes markdown execution reports for completed live or simulation strategy runs.
 *
 * <p>The report service is intentionally separate from the adjustment engine so reporting failures
 * never change trading or simulation behavior.
 */
public final class StrategyRunReportService {
    private static final Logger LOG = Logger.getLogger(StrategyRunReportService.class.getName());
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(IST);
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(IST);

    private final Path reportsRoot;
    private final Gson gson;

    public StrategyRunReportService(Path reportsRoot) {
        this.reportsRoot = Objects.requireNonNull(reportsRoot, "reportsRoot must not be null");
        this.gson = new GsonBuilder().serializeNulls().create();
    }

    public StrategyRunReportRequest parseRequest(String json) {
        return gson.fromJson(json, StrategyRunReportRequest.class);
    }

    public Path writeReport(StrategyRunReportRequest request) throws IOException {
        Objects.requireNonNull(request, "request must not be null");
        Files.createDirectories(reportsRoot);
        Instant anchor = request.endTimeAsInstant() != null
                ? request.endTimeAsInstant()
                : request.startTimeAsInstant() != null ? request.startTimeAsInstant() : Instant.now();
        String mode = sanitizeFilenameToken(request.mode());
        String filename = "strategy-run-" + FILE_TS.format(anchor) + "-" + mode + ".md";
        Path target = reportsRoot.resolve(filename);
        Files.writeString(target, render(request), StandardCharsets.UTF_8);
        return target;
    }

    public Path writeReportSafely(StrategyRunReportRequest request) {
        try {
            return writeReport(request);
        } catch (RuntimeException | IOException exception) {
            LOG.log(Level.WARNING, "Unable to write strategy run report", exception);
            return null;
        }
    }

    String render(StrategyRunReportRequest request) {
        StringBuilder markdown = new StringBuilder(4096);
        List<TimelineEvent> timeline = request.timeline() == null ? List.of() : request.timeline().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TimelineEvent::timestampAsInstant, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<LegSnapshot> initialLegs = request.initialStructure() == null ? List.of() : request.initialStructure();
        List<LegSnapshot> finalLegs = request.finalStructure() == null ? List.of() : request.finalStructure();

        BigDecimal bookedPnl = request.bookedPnl() != null
                ? request.bookedPnl()
                : finalLegs.stream().map(LegSnapshot::bookedPnl).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal livePnl = request.livePnl() != null
                ? request.livePnl()
                : finalLegs.stream().map(LegSnapshot::livePnl).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPnl = request.totalPnl() != null ? request.totalPnl() : PositionPnlCalculator.totalPnl(bookedPnl, livePnl);

        markdown.append("# Strategy Run Report\n\n");

        markdown.append("## Run Metadata\n");
        appendBullet(markdown, "Run id", request.runId());
        appendBullet(markdown, "Session id", request.sessionId());
        appendBullet(markdown, "Mode", request.mode());
        appendBullet(markdown, "Underlying", request.underlying());
        appendBullet(markdown, "Strategy name", request.strategyName());
        appendBullet(markdown, "Start time", formatInstant(request.startTimeAsInstant()));
        appendBullet(markdown, "End time", formatInstant(request.endTimeAsInstant()));
        appendBullet(markdown, "Total duration", formatDuration(request.durationMs()));
        appendBullet(markdown, "Initial max lots", Integer.toString(request.initialMaxLots()));
        appendBullet(markdown, "Lot size", Integer.toString(request.lotSize()));
        markdown.append('\n');

        markdown.append("## Initial Structure\n");
        if (initialLegs.isEmpty()) {
            markdown.append("_No starting legs were captured._\n\n");
        } else {
            markdown.append("| Leg Id | Label | Side | Type | Strike | Expiry | Entry Price | Initial Qty | Initial Delta |\n");
            markdown.append("| --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: |\n");
            for (LegSnapshot leg : initialLegs) {
                markdown.append("| ")
                        .append(md(leg.legId())).append(" | ")
                        .append(md(leg.label())).append(" | ")
                        .append(md(leg.side())).append(" | ")
                        .append(md(leg.optionType())).append(" | ")
                        .append(num(leg.strike())).append(" | ")
                        .append(md(leg.expiryDate())).append(" | ")
                        .append(num(leg.entryPrice())).append(" | ")
                        .append(leg.initialQuantity()).append(" | ")
                        .append(num(leg.initialDelta())).append(" |\n");
            }
            markdown.append('\n');
        }

        markdown.append("## Adjustment Timeline\n");
        if (timeline.isEmpty()) {
            markdown.append("_No actions were recorded for this run._\n\n");
        } else {
            markdown.append("| Timestamp | Action | Leg | Strike | Old Qty | New Qty | Lots Before | Lots After | Trigger | Reason Code |\n");
            markdown.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
            for (TimelineEvent event : timeline) {
                markdown.append("| ")
                        .append(md(formatInstant(event.timestampAsInstant()))).append(" | ")
                        .append(md(displayActionType(event))).append(" | ")
                        .append(md(event.legLabel())).append(" | ")
                        .append(num(event.strike())).append(" | ")
                        .append(event.oldQuantity()).append(" | ")
                        .append(event.newQuantity()).append(" | ")
                        .append(event.totalLotsBefore()).append(" | ")
                        .append(event.totalLotsAfter()).append(" | ")
                        .append(md(event.triggerType())).append(" | ")
                        .append(md(event.reasonCode())).append(" |\n");
            }
            markdown.append('\n');
            for (TimelineEvent event : timeline) {
                markdown.append("### ")
                        .append(formatInstant(event.timestampAsInstant()))
                        .append(" - ")
                        .append(displayActionType(event))
                        .append('\n');
                appendBullet(markdown, "Selected leg", joinLabel(event.legLabel(), event.optionType(), event.side(), event.strike()));
                appendBullet(markdown, "Reason", event.reasonCode());
                appendBullet(markdown, "Explanation", event.message());
                markdown.append('\n');
            }
        }

        markdown.append("## Signal Snapshot Per Adjustment\n");
        if (timeline.isEmpty()) {
            markdown.append("_No signal snapshots were recorded._\n\n");
        } else {
            for (TimelineEvent event : timeline) {
                markdown.append("### ").append(formatInstant(event.timestampAsInstant())).append(" - ").append(displayActionType(event)).append('\n');
                appendBullet(markdown, "Net delta before", num(event.netDelta2m()));
                appendBullet(markdown, "Post-action net delta", num(event.postAdjNetDelta()));
                appendBullet(markdown, "Delta improvement", num(event.improvementAbsDelta()));
                appendBullet(markdown, "Underlying direction", event.underlyingDirection());
                appendBullet(markdown, "Profit alignment", event.profitAlignment());
                appendBullet(markdown, "Live PnL slope", joinPnlSlope(event.livePnlChange2mPoints(), event.livePnlChange5mPoints()));
                appendBullet(markdown, "Volume confirmation", volumeStatus(event.volumeConfirmed(), event.volumeBypassed()));
                appendBullet(markdown, "Theta score", num(event.thetaScore()));
                appendBullet(markdown, "Liquidity score", num(event.liquidityScore()));
                appendBullet(markdown, "Candidate score", num(event.score()));
                appendBullet(markdown, "Churn guard status", churnStatus(event.reasonCode()));
                markdown.append('\n');
            }
        }

        markdown.append("## PnL Summary\n");
        appendBullet(markdown, "Booked PnL", money(bookedPnl));
        appendBullet(markdown, "Live / unrealized PnL", money(livePnl));
        appendBullet(markdown, "Total PnL", money(totalPnl));
        markdown.append('\n');
        if (!finalLegs.isEmpty()) {
            markdown.append("| Leg | Booked PnL | Live PnL | Total PnL |\n");
            markdown.append("| --- | ---: | ---: | ---: |\n");
            for (LegSnapshot leg : finalLegs) {
                markdown.append("| ")
                        .append(md(leg.label())).append(" | ")
                        .append(money(leg.bookedPnl())).append(" | ")
                        .append(money(leg.livePnl())).append(" | ")
                        .append(money(leg.totalPnl())).append(" |\n");
            }
            markdown.append('\n');
        }
        if (!timeline.isEmpty()) {
            markdown.append("| Timestamp | Action | Leg | Booked PnL Impact |\n");
            markdown.append("| --- | --- | --- | ---: |\n");
            for (TimelineEvent event : timeline) {
                markdown.append("| ")
                        .append(md(formatInstant(event.timestampAsInstant()))).append(" | ")
                        .append(md(displayActionType(event))).append(" | ")
                        .append(md(event.legLabel())).append(" | ")
                        .append(money(event.bookedPnlImpact())).append(" |\n");
            }
            markdown.append('\n');
        }

        markdown.append("## Final Structure\n");
        if (finalLegs.isEmpty()) {
            markdown.append("_No final legs were captured._\n\n");
        } else {
            markdown.append("| Leg Id | Label | Side | Type | Strike | Expiry | Entry Price | Final Open Qty | Booked PnL | Live PnL | Total PnL | Status |\n");
            markdown.append("| --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
            for (LegSnapshot leg : finalLegs) {
                markdown.append("| ")
                        .append(md(leg.legId())).append(" | ")
                        .append(md(leg.label())).append(" | ")
                        .append(md(leg.side())).append(" | ")
                        .append(md(leg.optionType())).append(" | ")
                        .append(num(leg.strike())).append(" | ")
                        .append(md(leg.expiryDate())).append(" | ")
                        .append(num(leg.entryPrice())).append(" | ")
                        .append(leg.finalQuantity()).append(" | ")
                        .append(money(leg.bookedPnl())).append(" | ")
                        .append(money(leg.livePnl())).append(" | ")
                        .append(money(leg.totalPnl())).append(" | ")
                        .append(md(leg.status())).append(" |\n");
            }
            markdown.append('\n');
        }

        markdown.append("## Adjustment Decision Summary\n");
        appendBullet(markdown, "Total ADD actions", Integer.toString(countByAction(timeline, "ADD")));
        appendBullet(markdown, "Total REDUCE actions", Integer.toString(countByAction(timeline, "REDUCE")));
        appendBullet(markdown, "Total manual exits", Integer.toString(countByAction(timeline, "MANUAL_EXIT") + countByAction(timeline, "EXIT_ALL")));
        appendBullet(markdown, "Skipped adjustments by reason", summarizeReasonCounts(timeline, "SKIP", "DELAYED"));
        appendBullet(markdown, "Delayed adjustments", Integer.toString(countByAction(timeline, "DELAYED")));
        appendBullet(markdown, "Hard triggers", Integer.toString(countByTrigger(timeline, "HARD")));
        appendBullet(markdown, "Normal triggers", Integer.toString(countByTrigger(timeline, "NORMAL")));
        appendBullet(markdown, "Churn guard blocks", Integer.toString((int) timeline.stream().filter(event -> "churn_guard_active".equalsIgnoreCase(event.reasonCode())).count()));
        markdown.append('\n');

        markdown.append("## Observations\n");
        for (String observation : buildObservations(request, timeline, finalLegs)) {
            markdown.append("- ").append(observation).append('\n');
        }
        markdown.append('\n');

        return markdown.toString();
    }

    private static List<String> buildObservations(
            StrategyRunReportRequest request,
            List<TimelineEvent> timeline,
            List<LegSnapshot> finalLegs
    ) {
        List<String> observations = new ArrayList<>();
        TimelineEvent firstDeltaEvent = timeline.stream().filter(event -> event.netDelta2m() != null).findFirst().orElse(null);
        TimelineEvent lastDeltaEvent = timeline.stream()
                .filter(event -> event.postAdjNetDelta() != null)
                .reduce((left, right) -> right)
                .orElse(null);
        if (firstDeltaEvent != null && lastDeltaEvent != null) {
            boolean improved = firstDeltaEvent.netDelta2m().abs().compareTo(lastDeltaEvent.postAdjNetDelta().abs()) > 0;
            observations.add(improved
                    ? "Net delta improved over the recorded adjustment sequence."
                    : "Net delta did not improve across the full recorded adjustment sequence.");
        } else {
            observations.add("Net delta change could not be fully evaluated from the recorded events.");
        }

        int finalOpenQuantity = finalLegs.stream()
                .map(LegSnapshot::finalQuantity)
                .mapToInt(Integer::intValue)
                .sum();
        int peakLots = timeline.stream()
                .map(TimelineEvent::totalLotsAfter)
                .max(Integer::compareTo)
                .orElse(0);
        int finalLots = request.lotSize() <= 0 ? 0 : finalOpenQuantity / Math.max(1, request.lotSize());
        int maxObservedLots = Math.max(peakLots, finalLots);
        observations.add(maxObservedLots <= request.initialMaxLots()
                ? "Total open quantity stayed within the configured max lot cap."
                : "Total open quantity exceeded the configured max lot cap.");

        boolean anyWorsened = timeline.stream().anyMatch(event ->
                event.netDelta2m() != null
                        && event.postAdjNetDelta() != null
                        && event.postAdjNetDelta().abs().compareTo(event.netDelta2m().abs()) > 0);
        observations.add(anyWorsened
                ? "At least one recorded action worsened absolute net delta."
                : "No recorded action worsened absolute net delta.");

        boolean missingDataSkip = timeline.stream().anyMatch(event -> {
            String reasonCode = event.reasonCode();
            return reasonCode != null && (reasonCode.contains("missing") || reasonCode.contains("volume_not_confirmed"));
        });
        observations.add(missingDataSkip
                ? "Missing or incomplete live data contributed to at least one skip."
                : "No skips were attributed to missing live data.");

        boolean cooldownBlocked = timeline.stream().anyMatch(event -> "cooldown_active".equalsIgnoreCase(event.reasonCode()));
        boolean churnBlocked = timeline.stream().anyMatch(event -> "churn_guard_active".equalsIgnoreCase(event.reasonCode()));
        observations.add((cooldownBlocked || churnBlocked)
                ? "Cooldown or churn guard protections blocked at least one action."
                : "Cooldown and churn guard protections did not block any recorded action.");
        return observations;
    }

    private static int countByAction(List<TimelineEvent> timeline, String action) {
        return (int) timeline.stream().filter(event -> action.equalsIgnoreCase(displayActionType(event))).count();
    }

    private static int countByTrigger(List<TimelineEvent> timeline, String triggerType) {
        return (int) timeline.stream().filter(event -> triggerType.equalsIgnoreCase(event.triggerType())).count();
    }

    private static String summarizeReasonCounts(List<TimelineEvent> timeline, String... actionTypes) {
        java.util.Map<String, Long> counts = timeline.stream()
                .filter(event -> {
                    for (String actionType : actionTypes) {
                        if (actionType.equalsIgnoreCase(displayActionType(event))) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.groupingBy(
                        event -> event.reasonCode() == null || event.reasonCode().isBlank() ? "unspecified" : event.reasonCode(),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));
        if (counts.isEmpty()) {
            return "None";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("None");
    }

    private static String displayActionType(TimelineEvent event) {
        String adjustmentActionType = normalizeAction(event.adjustmentActionType());
        if (!adjustmentActionType.isEmpty()) {
            return adjustmentActionType;
        }
        String baseAction = normalizeAction(event.actionType());
        return switch (baseAction) {
            case "MANUAL_EXIT_SELECTED" -> "MANUAL_EXIT";
            case "MANUAL_EXIT_ALL" -> "EXIT_ALL";
            case "DELTA_ADJUSTMENT" -> "ADJUSTMENT";
            default -> baseAction.isEmpty() ? "UNKNOWN" : baseAction;
        };
    }

    private static String normalizeAction(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String churnStatus(String reasonCode) {
        if ("churn_guard_active".equalsIgnoreCase(reasonCode)) {
            return "Blocked";
        }
        return "Clear";
    }

    private static String volumeStatus(Boolean confirmed, Boolean bypassed) {
        if (Boolean.TRUE.equals(confirmed) && Boolean.TRUE.equals(bypassed)) {
            return "Confirmed, bypassed";
        }
        if (Boolean.TRUE.equals(confirmed)) {
            return "Confirmed";
        }
        if (Boolean.TRUE.equals(bypassed)) {
            return "Bypassed";
        }
        return "Not confirmed";
    }

    private static String joinPnlSlope(BigDecimal pnl2m, BigDecimal pnl5m) {
        return "2m " + num(pnl2m) + " / 5m " + num(pnl5m);
    }

    private static String joinLabel(String legLabel, String optionType, String side, BigDecimal strike) {
        return String.join(" / ", List.of(
                fallback(legLabel),
                fallback(optionType),
                fallback(side),
                num(strike)
        ));
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static void appendBullet(StringBuilder markdown, String label, String value) {
        markdown.append("- **").append(label).append("**: ").append(md(value)).append('\n');
    }

    private static String md(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\n", " ").trim();
    }

    private static String num(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString() + " Rs";
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "-" : DISPLAY_TS.format(instant);
    }

    private static String formatDuration(long durationMs) {
        if (durationMs <= 0) {
            return "-";
        }
        Duration duration = Duration.ofMillis(durationMs);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static String sanitizeFilenameToken(String mode) {
        String raw = mode == null || mode.isBlank() ? "run" : mode.trim().toLowerCase(Locale.ROOT);
        String sanitized = raw.replaceAll("[^a-z0-9._-]+", "-");
        return sanitized.isBlank() ? "run" : sanitized;
    }

    public record StrategyRunReportRequest(
            String runId,
            String sessionId,
            String mode,
            String underlying,
            String strategyName,
            String startTime,
            String endTime,
            long durationMs,
            int initialMaxLots,
            int lotSize,
            BigDecimal liveSpot,
            BigDecimal finalNetPremiumPoints,
            BigDecimal bookedPnl,
            BigDecimal livePnl,
            BigDecimal totalPnl,
            List<LegSnapshot> initialStructure,
            List<LegSnapshot> finalStructure,
            List<TimelineEvent> timeline
    ) {
        public Instant startTimeAsInstant() {
            return parseInstant(startTime);
        }

        public Instant endTimeAsInstant() {
            return parseInstant(endTime);
        }
    }

    public record LegSnapshot(
            String legId,
            String label,
            String side,
            String optionType,
            BigDecimal strike,
            String expiryDate,
            BigDecimal entryPrice,
            BigDecimal marketPrice,
            int initialQuantity,
            int finalQuantity,
            BigDecimal initialDelta,
            BigDecimal bookedPnl,
            BigDecimal livePnl,
            BigDecimal totalPnl,
            String status
    ) {
    }

    public record TimelineEvent(
            String timestamp,
            String actionType,
            String adjustmentActionType,
            String legId,
            String legLabel,
            String optionType,
            String side,
            BigDecimal strike,
            int oldQuantity,
            int newQuantity,
            int totalLotsBefore,
            int totalLotsAfter,
            String triggerType,
            String reasonCode,
            String reason,
            String message,
            BigDecimal bookedPnlImpact,
            BigDecimal delta2m,
            BigDecimal delta5m,
            BigDecimal deltaSod,
            BigDecimal livePnlPoints,
            BigDecimal livePnlChange2mPoints,
            BigDecimal livePnlChange5mPoints,
            BigDecimal netDelta2m,
            BigDecimal netDelta5m,
            BigDecimal netDeltaSod,
            BigDecimal postAdjNetDelta,
            BigDecimal improvementAbsDelta,
            BigDecimal improvementRatio,
            String underlyingDirection,
            String profitAlignment,
            Boolean volumeConfirmed,
            Boolean volumeBypassed,
            BigDecimal thetaScore,
            BigDecimal liquidityScore,
            BigDecimal score
    ) {
        public Instant timestampAsInstant() {
            return parseInstant(timestamp);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
