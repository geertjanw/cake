package com.cakeandcandles.cake;

import com.cakeandcandles.cake.BakeryExceptions.UnknownFlavorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CakeFlavorsTest {

    private final CakeFlavors flavors =
            new CakeFlavors(Set.of("chocolate", "vanilla", "strawberry", "lemon"));

    @Test
    @DisplayName("serves the configured flavors")
    void servesConfiguredFlavors() {
        assertThat(flavors.serves("chocolate")).isTrue();
        assertThat(flavors.serves("lemon")).isTrue();
    }

    @Test
    @DisplayName("does not serve anything else")
    void doesNotServeUnknownFlavors() {
        assertThat(flavors.serves("unicorn")).isFalse();
    }

    @Test
    @DisplayName("matching is exact, so case variants do not slip through")
    void matchingIsExact() {
        // Each variant a caller can invent is a time series if it gets through, and the
        // inventory lookup is case-sensitive anyway - "Chocolate" would find no stock.
        assertThat(flavors.serves("Chocolate")).isFalse();
        assertThat(flavors.serves("CHOCOLATE")).isFalse();
        assertThat(flavors.serves(" chocolate")).isFalse();
    }

    @Test
    @DisplayName("require() passes a served flavor through")
    void requireAcceptsServedFlavor() {
        assertThatCode(() -> flavors.require("vanilla")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("require() rejects an unserved flavor")
    void requireRejectsUnknownFlavor() {
        assertThatThrownBy(() -> flavors.require("unicorn"))
                .isInstanceOf(UnknownFlavorException.class);
    }

    @Test
    @DisplayName("the rejection names what we do serve, in a stable order")
    void rejectionListsServedFlavors() {
        // Sorted, not Set iteration order: an error message that reshuffles between runs is
        // a nuisance to match in tests and to read in logs.
        assertThatThrownBy(() -> flavors.require("unicorn"))
                .hasMessageContaining("unicorn")
                .hasMessageContaining("chocolate, lemon, strawberry, vanilla");
    }

    @Test
    @DisplayName("served() is sorted regardless of the configured order")
    void servedIsSorted() {
        CakeFlavors shuffled = new CakeFlavors(Set.of("vanilla", "chocolate", "lemon"));

        assertThat(shuffled.served()).containsExactly("chocolate", "lemon", "vanilla");
    }

    @Test
    @DisplayName("an empty configuration serves nothing rather than everything")
    void emptyConfigurationServesNothing() {
        // Fail closed: a missing config should not silently reopen the cardinality hole.
        CakeFlavors none = new CakeFlavors(Set.of());

        assertThat(none.serves("chocolate")).isFalse();
        assertThatThrownBy(() -> none.require("chocolate"))
                .isInstanceOf(UnknownFlavorException.class);
    }
}
