package com.cakeandcandles.cake;

import com.cakeandcandles.cake.BakeryExceptions.OutOfStockException;
import com.cakeandcandles.cake.BakeryExceptions.OvenTimeoutException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bakery. HTTP and JDBC spans come for free from the OpenTelemetry Java agent.
 * What we add by hand here is the *business* context: a "bake" span with cake attributes,
 * a bake-duration histogram, and counters for cakes baked and oven failures.
 */
@Service
public class Bakery {

    private static final Logger log = LoggerFactory.getLogger(Bakery.class);

    // Attribute keys reused across spans and metrics. Keep cardinality low: flavor is a
    // small fixed set, candles is a small integer. Never put partyId on a metric.
    static final AttributeKey<String> FLAVOR = AttributeKey.stringKey("cake.flavor");
    static final AttributeKey<Long> CANDLES = AttributeKey.longKey("cake.candles");
    static final AttributeKey<String> PARTY_ID = AttributeKey.stringKey("party.id");

    private final JdbcTemplate jdbc;
    private final int maxCandles;
    private final long millisPerCandle;

    private final Tracer tracer;
    private final LongCounter cakesBaked;
    private final LongCounter candlesLit;
    private final LongCounter ovenFailures;
    private final DoubleHistogram bakeDuration;

    public Bakery(JdbcTemplate jdbc,
                  @Value("${bakery.oven.max-candles:80}") int maxCandles,
                  @Value("${bakery.oven.millis-per-candle:25}") long millisPerCandle) {
        this.jdbc = jdbc;
        this.maxCandles = maxCandles;
        this.millisPerCandle = millisPerCandle;

        this.tracer = GlobalOpenTelemetry.getTracer("cake-service");
        Meter meter = GlobalOpenTelemetry.getMeter("cake-service");
        this.cakesBaked = meter.counterBuilder("cakes.baked")
                .setDescription("Number of cakes successfully baked").setUnit("{cake}").build();
        this.candlesLit = meter.counterBuilder("candles.lit")
                .setDescription("Total candles placed on cakes").setUnit("{candle}").build();
        this.ovenFailures = meter.counterBuilder("oven.failures")
                .setDescription("Bakes that failed").setUnit("{failure}").build();
        this.bakeDuration = meter.histogramBuilder("bake.duration")
                .setDescription("Time spent baking a cake").setUnit("s").build();
    }

    @Transactional
    public CakeResponse bake(CakeRequest req) {
        // Custom span wrapping the whole bake, nested under the agent's HTTP server span.
        Span span = tracer.spanBuilder("bake cake")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(FLAVOR, req.flavor())
                .setAttribute(CANDLES, (long) req.candles())
                .setAttribute(PARTY_ID, req.partyId())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            reserveFlavor(req.flavor());            // JDBC spans appear under this one
            long bakeMillis = runOven(req);          // sleeps; the slow bit you see in traces
            String cakeId = UUID.randomUUID().toString();
            span.setAttribute("cake.id", cakeId);

            Attributes attrs = Attributes.of(FLAVOR, req.flavor());
            cakesBaked.add(1, attrs);
            candlesLit.add(req.candles(), attrs);
            bakeDuration.record(bakeMillis / 1000.0, attrs);

            log.info("Baked a {} cake with {} candles for party {} in {} ms",
                    req.flavor(), req.candles(), req.partyId(), bakeMillis);
            return new CakeResponse(cakeId, req.flavor(), req.candles(), bakeMillis);
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            ovenFailures.add(1, Attributes.of(FLAVOR, req.flavor(),
                    AttributeKey.stringKey("failure.reason"), reasonOf(e)));
            throw e;
        } finally {
            span.end();
        }
    }

    private void reserveFlavor(String flavor) {
        Integer stock = jdbc.query(
                "SELECT stock FROM flavor_inventory WHERE flavor = ? FOR UPDATE",
                (ResultSetExtractor<Integer>) rs -> rs.next() ? rs.getInt("stock") : null, flavor);
        if (stock == null) {
            throw new OutOfStockException(flavor + " (unknown flavor)");
        }
        if (stock <= 0) {
            log.warn("Out of {} - refusing order", flavor);
            throw new OutOfStockException(flavor);
        }
        jdbc.update("UPDATE flavor_inventory SET stock = stock - 1 WHERE flavor = ?", flavor);
        Span.current().addEvent("flavor reserved",
                Attributes.of(FLAVOR, flavor, AttributeKey.longKey("inventory.remaining"), (long) stock - 1));
    }

    private long runOven(CakeRequest req) {
        Span oven = tracer.spanBuilder("oven")
                .setAttribute(CANDLES, (long) req.candles())
                .startSpan();
        try (Scope ignored = oven.makeCurrent()) {
            if (req.candles() > maxCandles) {
                // Lots of candles = old cake = long bake = timeout. A non-retryable failure.
                sleep(2_000);
                throw new OvenTimeoutException(req.candles());
            }
            long millis = 150 + req.candles() * millisPerCandle;
            sleep(millis);
            return millis;
        } catch (RuntimeException e) {
            oven.recordException(e);
            oven.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            oven.end();
        }
    }

    public List<Map<String, Object>> inventory() {
        return jdbc.queryForList("SELECT flavor, stock FROM flavor_inventory ORDER BY flavor");
    }

    public Map<String, Object> restock(String flavor, int amount) {
        int updated = jdbc.update("UPDATE flavor_inventory SET stock = stock + ? WHERE flavor = ?", amount, flavor);
        if (updated == 0) {
            jdbc.update("INSERT INTO flavor_inventory (flavor, stock) VALUES (?, ?)", flavor, amount);
        }
        log.info("Restocked {} by {}", flavor, amount);
        return Map.of("flavor", flavor, "stock",
                jdbc.queryForObject("SELECT stock FROM flavor_inventory WHERE flavor = ?", Integer.class, flavor));
    }

    private static String reasonOf(RuntimeException e) {
        if (e instanceof OutOfStockException) return "out_of_stock";
        if (e instanceof OvenTimeoutException) return "oven_timeout";
        return "unknown";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while baking", e);
        }
    }
}
