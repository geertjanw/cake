package com.cakeandcandles.party;

import com.cakeandcandles.party.PartyExceptions.UnknownFlavorException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * The flavors the bakery serves, as far as party-service knows.
 *
 * <p>This deliberately duplicates cake-service's list rather than asking it at runtime.
 * party-service puts {@code cake.flavor} on its own {@code parties.planned} counter, so it
 * needs the set bounded on its own terms - relying on the downstream 400 would mean the
 * attribute is only safe as long as the other service stays reachable and keeps validating,
 * which is not a property to bet a metrics bill on. Rejecting here also saves a round trip.
 */
@Component
public class CakeFlavors {

    private final Set<String> served;

    public CakeFlavors(@Value("${parties.cake.flavors}") Set<String> served) {
        this.served = Set.copyOf(served);
    }

    public boolean serves(String flavor) {
        return served.contains(flavor);
    }

    /** Sorted for a stable error message - Set.copyOf does not preserve order. */
    public Set<String> served() {
        return new TreeSet<>(served);
    }

    /** @throws UnknownFlavorException if the bakery does not serve it, which maps to 400. */
    public void require(String flavor) {
        if (!serves(flavor)) {
            throw new UnknownFlavorException(flavor, served());
        }
    }
}
