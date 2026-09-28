package com.cakeandcandles.cake;

import com.cakeandcandles.cake.BakeryExceptions.OutOfStockException;
import com.cakeandcandles.cake.BakeryExceptions.OvenTimeoutException;
import com.cakeandcandles.time.Sleeper;
import com.cakeandcandles.time.Stopwatch;
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
import io.opentelemetry.semconv.ErrorAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    // Attribute keys reused across spans and metrics. Candles is a small integer, and
    // flavor is bounded by CakeFlavors before it reaches a metric. Never put partyId on
    // a metric: it is unique per request, so every party would be its own time series.
    static final AttributeKey<String> FLAVOR = AttributeKey.stringKey("cake.flavor");
    static final AttributeKey<Long> CANDLES = AttributeKey.longKey("cake.candles");
    static final AttributeKey<String> PARTY_ID = AttributeKey.stringKey("party.id");

    private final JdbcTemplate jdbc;
    private final CakeFlavors flavors;
    private final int maxCandles;
    private final long millisPerCandle;

    private final Tracer tracer;
    private final LongCounter cakesBaked;
    private final LongCounter candlesLit;
    private final LongCounter ovenFailures;
    private final DoubleHistogram bakeDuration;

    public Bakery(JdbcTemplate jdbc,
                  CakeFlavors flavors,
                  @Value("${bakery.oven.max-candles:80}") int maxCandles,
                  @Value("${bakery.oven.millis-per-candle:25}") long millisPerCandle) {
        this.jdbc = jdbc;
        this.flavors = flavors;
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
                .setDescription("Measured wall-clock time of a bake")
                .setUnit("s").build();
    }

    @Transactional
    public CakeResponse bake(CakeRequest req) {
        // Before anything is recorded: an unserved flavor is a bad request, not a failed
        // bake, and rejecting it here is what keeps cake.flavor bounded on every metric
        // below. No span, no counter - nothing a caller can use to mint a time series.
        flavors.require(req.flavor());

        // Custom span wrapping the whole bake, nested under the agent's HTTP server span.
        Span span = tracer.spanBuilder("bake cake")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(FLAVOR, req.flavor())
                .setAttribute(CANDLES, (long) req.candles())
                .setAttribute(PARTY_ID, req.partyId())
                .startSpan();
        Stopwatch timer = Stopwatch.start();
        try (Scope ignored = span.makeCurrent()) {
            reserveFlavor(req.flavor());            // JDBC spans appear under this one
            Duration bakeTime = runOven(req);        // sleeps; the slow bit you see in traces
            String cakeId = UUID.randomUUID().toString();
            span.setAttribute("cake.id", cakeId);

            Attributes attrs = Attributes.of(FLAVOR, req.flavor());
            cakesBaked.add(1, attrs);
            candlesLit.add(req.candles(), attrs);

            log.debug("Baked a {} cake with {} candles for party {} in {} ms",
                    req.flavor(), req.candles(), req.partyId(), bakeTime.toMillis());
            // This is application code, not an instrumentation library, so we are the ones
            // entitled to say the operation genuinely succeeded rather than leaving it UNSET.
            span.setStatus(StatusCode.OK);
            return new CakeResponse(cakeId, req.flavor(), req.candles(), bakeTime.toMillis());
        } catch (RuntimeException e) {
            // Both failure modes here are expected business outcomes, not faults.
            log.warn("Bake failed for party {} ({} cake, {} candles)",
                    req.partyId(), req.flavor(), req.candles(), e);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            ovenFailures.add(1, Attributes.of(FLAVOR, req.flavor(),
                    ErrorAttributes.ERROR_TYPE, reasonOf(e)));
            throw e;
        } finally {
            // Recorded for failures too: a bake that died after 2 seconds in the oven is
            // part of the latency story, and leaving it out makes the histogram flatter
            // than the service really is. Which failure it was belongs on oven.failures.
            bakeDuration.record(timer.elapsedSeconds(), Attributes.of(FLAVOR, req.flavor()));
            span.end();
        }
    }

    private void reserveFlavor(String flavor) {
        Integer stock = jdbc.query(
                "SELECT stock FROM flavor_inventory WHERE flavor = ? FOR UPDATE",
                (ResultSetExtractor<Integer>) rs -> rs.next() ? rs.getInt("stock") : null, flavor);
        if (stock == null) {
            // Unknown flavors are rejected before we get here, so this means a flavor we
            // advertise has no inventory row at all: bakery.cake.flavors and data.sql have
            // drifted apart. Treated as out of stock, but it is really a seeding bug.
            log.error("{} is a served flavor but has no inventory row", flavor);
            throw new OutOfStockException(flavor + " (no inventory row)");
        }
        if (stock <= 0) {
            log.warn("Out of {} - refusing order", flavor);
            throw new OutOfStockException(flavor);
        }
        jdbc.update("UPDATE flavor_inventory SET stock = stock - 1 WHERE flavor = ?", flavor);
        Span.current().addEvent("flavor reserved",
                Attributes.of(FLAVOR, flavor, AttributeKey.longKey("inventory.remaining"), (long) stock - 1));
    }

    private Duration runOven(CakeRequest req) {
        Span oven = tracer.spanBuilder("oven")
                .setAttribute(CANDLES, (long) req.candles())
                .startSpan();
        Stopwatch timer = Stopwatch.start();
        try (Scope ignored = oven.makeCurrent()) {
            if (req.candles() > maxCandles) {
                // Lots of candles = old cake = long bake = timeout. A non-retryable failure.
                Sleeper.sleep(Duration.ofSeconds(2));
                throw new OvenTimeoutException(req.candles());
            }
            Sleeper.sleep(Duration.ofMillis(150 + req.candles() * millisPerCandle));
            oven.setStatus(StatusCode.OK);
            // How long the oven *actually* took, not how long we asked it to sleep.
            return timer.elapsed();
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
        // Same guard as ordering: restocking a flavor we do not serve would create an
        // inventory row that nothing can ever order.
        flavors.require(flavor);
        int updated = jdbc.update("UPDATE flavor_inventory SET stock = stock + ? WHERE flavor = ?", amount, flavor);
        if (updated == 0) {
            jdbc.update("INSERT INTO flavor_inventory (flavor, stock) VALUES (?, ?)", flavor, amount);
        }
        log.info("Restocked {} by {}", flavor, amount);
        return Map.of("flavor", flavor, "stock",
                jdbc.queryForObject("SELECT stock FROM flavor_inventory WHERE flavor = ?", Integer.class, flavor));
    }

    /** Low-cardinality error.type values. Semconv reserves "_OTHER" for anything unrecognised. */
    private static String reasonOf(RuntimeException e) {
        if (e instanceof OutOfStockException) return "out_of_stock";
        if (e instanceof OvenTimeoutException) return "oven_timeout";
        return ErrorAttributes.ErrorTypeValues.OTHER;
    }
}
