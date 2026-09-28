package com.cakeandcandles.cake;

import com.cakeandcandles.cake.BakeryExceptions.UnknownFlavorException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * The flavors this bakery serves, and the reason {@code cake.flavor} is safe to put on a
 * metric.
 *
 * <p>Flavor arrives in the request body validated only as {@code @NotBlank}, so without a
 * check here a client could post any string it liked. On a span that is harmless - spans
 * are sampled, they expire, and the exact value is what you want when debugging one
 * request. On a metric it is not: every distinct attribute value is a new time series that
 * persists, so an unbounded attribute is an unbounded cost, and a trivial one to run up by
 * accident or on purpose.
 *
 * <p>Rejecting unknown flavors at the edge keeps that set bounded by construction, so
 * everything downstream can use the raw value without thinking about it.
 */
@Component
public class CakeFlavors {

    private final Set<String> served;

    public CakeFlavors(@Value("${bakery.cake.flavors}") Set<String> served) {
        this.served = Set.copyOf(served);
    }

    public boolean serves(String flavor) {
        return served.contains(flavor);
    }

    /** Sorted for a stable error message - Set.copyOf does not preserve order. */
    public Set<String> served() {
        return new TreeSet<>(served);
    }

    /** @throws UnknownFlavorException if we do not serve it, which the API maps to 400. */
    public void require(String flavor) {
        if (!serves(flavor)) {
            throw new UnknownFlavorException(flavor, served());
        }
    }
}
