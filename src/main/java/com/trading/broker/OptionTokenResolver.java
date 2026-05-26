package com.trading.broker;

import com.angelbroking.smartapi.SmartConnect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.strategy.InstrumentConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves NFO option/futures contracts from Angel's public instrument master.
 * This avoids authenticated searchScrip calls during live signal execution.
 */
public class OptionTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(OptionTokenResolver.class);

    private static final String SCRIP_MASTER_URL =
            "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<Instrument>> cache = new HashMap<>();
    private LocalDate lastLoaded;

    public OptionTokenResolver(SmartConnect smartConnect) {
        // SmartConnect is intentionally not used here. Keep the constructor
        // signature stable for TradingEngine while avoiding searchScrip quota.
    }

    /** Carries resolved NFO token, real broker trading symbol, and lot size. */
    public record OptionResolution(String token, String symbol, int lotSize) {}

    public synchronized void refreshIfNeeded() {
        if (lastLoaded != null && lastLoaded.equals(LocalDate.now()) && !cache.isEmpty()) {
            return;
        }
        try {
            load();
        } catch (Exception e) {
            log.error("Instrument master refresh failed: {}", e.getMessage());
        }
    }

    public synchronized boolean isLoaded() {
        return lastLoaded != null && !cache.isEmpty();
    }

    public OptionResolution resolve(String underlying, double ltp,
                                    String optionType, InstrumentConfig config) {
        refreshIfNeeded();

        String name = cleanUnderlying(underlying);
        List<Instrument> instruments = cache.get(name);
        if (instruments == null || instruments.isEmpty()) {
            log.warn("No NFO instruments in master for underlying: {}", name);
            return null;
        }

        String opt = clean(optionType);
        List<Instrument> options = instruments.stream()
                .filter(i -> opt.equals(i.optionType))
                .filter(i -> i.expiry != null && !i.expiry.isBefore(LocalDate.now()))
                .toList();
        if (options.isEmpty()) {
            log.warn("No {} option contracts in master for {}", opt, name);
            return null;
        }

        List<LocalDate> expiries = options.stream()
                .map(i -> i.expiry)
                .distinct()
                .sorted()
                .toList();
        LocalDate expiry = expiries.get(Math.min(config.expiryOffset(), expiries.size() - 1));

        List<Double> strikes = options.stream()
                .filter(i -> expiry.equals(i.expiry))
                .map(i -> i.strike)
                .distinct()
                .sorted()
                .toList();
        if (strikes.isEmpty()) {
            log.warn("No {} strikes for {} expiry={}", opt, name, expiry);
            return null;
        }

        double atm = strikes.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s - ltp)))
                .orElse(strikes.get(0));
        int atmIndex = strikes.indexOf(atm);
        int targetIndex = Math.max(0, Math.min(atmIndex + config.strikeOffset(), strikes.size() - 1));
        double targetStrike = strikes.get(targetIndex);

        Optional<Instrument> match = options.stream()
                .filter(i -> expiry.equals(i.expiry))
                .filter(i -> Double.compare(i.strike, targetStrike) == 0)
                .findFirst();
        if (match.isEmpty()) {
            log.warn("Option contract not found | {} {} expiry={} strike={}",
                    name, opt, expiry, targetStrike);
            return null;
        }

        Instrument inst = match.get();
        log.info("Option resolved | {} {} @ Rs.{} expiry={} strike={} token={} symbol={} lotSize={}",
                name, opt, String.format("%.2f", ltp), expiry, targetStrike,
                inst.token, inst.symbol, inst.lotSize);
        return new OptionResolution(inst.token, inst.symbol, inst.lotSize);
    }

    public OptionResolution resolveFutures(String underlying, int expiryOffset) {
        refreshIfNeeded();

        String name = cleanUnderlying(underlying);
        List<Instrument> instruments = cache.get(name);
        if (instruments == null || instruments.isEmpty()) {
            log.warn("No NFO instruments in master for underlying: {}", name);
            return null;
        }

        List<Instrument> futures = instruments.stream()
                .filter(i -> "FUT".equals(i.optionType))
                .filter(i -> i.expiry != null && !i.expiry.isBefore(LocalDate.now()))
                .sorted(Comparator.comparing(i -> i.expiry))
                .toList();
        if (futures.isEmpty()) {
            log.warn("No futures contracts in master for {}", name);
            return null;
        }

        Instrument inst = futures.get(Math.min(expiryOffset, futures.size() - 1));
        log.info("Futures resolved | {} expiry={} token={} symbol={} lotSize={}",
                name, inst.expiry, inst.token, inst.symbol, inst.lotSize);
        return new OptionResolution(inst.token, inst.symbol, inst.lotSize);
    }

    private synchronized void load() throws Exception {
        log.info("Downloading Angel One instrument master...");
        Request request = new Request.Builder()
                .url(SCRIP_MASTER_URL)
                .header("Accept", "application/json")
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Scrip master download failed: HTTP " + response.code());
            }
            String json = response.body().string();
            parseAndCache(json);
            lastLoaded = LocalDate.now();
            log.info("Instrument master ready | underlyings:{}", cache.size());
        }
    }

    private void parseAndCache(String json) throws Exception {
        Map<String, List<Instrument>> next = new HashMap<>();
        JsonNode root = MAPPER.readTree(json);

        int parsed = 0;
        int skipped = 0;
        for (JsonNode node : root) {
            String exchange = node.path("exch_seg").asText("");
            String instrumentType = node.path("instrumenttype").asText("");
            if (!"NFO".equals(exchange)) {
                skipped++;
                continue;
            }

            boolean option = instrumentType.startsWith("OPT");
            boolean future = instrumentType.startsWith("FUT");
            if (!option && !future) {
                skipped++;
                continue;
            }

            String symbol = node.path("symbol").asText("");
            String name = clean(node.path("name").asText(""));
            String token = node.path("token").asText("");
            LocalDate expiry = parseExpiry(node.path("expiry").asText(""));
            if (symbol.isBlank() || name.isBlank() || token.isBlank() || expiry == null) {
                skipped++;
                continue;
            }

            String optionType;
            double strike = 0;
            if (symbol.endsWith("CE")) {
                optionType = "CE";
                strike = parseStrikeFromSymbol(symbol);
            } else if (symbol.endsWith("PE")) {
                optionType = "PE";
                strike = parseStrikeFromSymbol(symbol);
            } else if (future || symbol.endsWith("FUT")) {
                optionType = "FUT";
            } else {
                skipped++;
                continue;
            }
            if (option && strike <= 0) {
                skipped++;
                continue;
            }

            int lotSize = parseInt(node.path("lotsize").asText("1"), 1);
            next.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Instrument(token, symbol, expiry, strike, optionType, lotSize));
            parsed++;
        }

        next.values().forEach(list -> list.sort(
                Comparator.comparing((Instrument i) -> i.expiry)
                        .thenComparingDouble(i -> i.strike)
                        .thenComparing(i -> i.symbol)));
        cache.clear();
        cache.putAll(next);

        log.info("Parsed Angel NFO instruments | parsed:{} skipped:{} underlyings:{}",
                parsed, skipped, cache.size());
    }

    private static final DateTimeFormatter[] EXPIRY_FORMATS = {
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("ddMMMyy").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    };

    private static LocalDate parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (DateTimeFormatter formatter : EXPIRY_FORMATS) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (Exception ignored) {
                // Try the next supported Angel date shape.
            }
        }
        return null;
    }

    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("^[A-Z0-9&-]+?\\d{2}[A-Z]{3}\\d{2}(\\d+)(CE|PE)$");

    private static double parseStrikeFromSymbol(String symbol) {
        Matcher matcher = SYMBOL_PATTERN.matcher(symbol);
        if (!matcher.matches()) return -1;
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String cleanUnderlying(String value) {
        String cleaned = clean(value)
                .replace("-EQ", "")
                .replace("-BE", "")
                .replace("-SM", "")
                .trim();
        cleaned = cleaned.replaceAll("\\d{2}[A-Z]{3}\\d{0,4}FUT$", "").trim();
        if (cleaned.endsWith("FUT")) {
            cleaned = cleaned.replaceAll("[0-9A-Z]*FUT$", "").trim();
        }
        return cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private record Instrument(String token, String symbol, LocalDate expiry,
                              double strike, String optionType, int lotSize) {}
}
