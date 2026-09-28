package com.cakeandcandles.party;

import com.cakeandcandles.party.CakeClient.CakeException;
import com.cakeandcandles.party.CakeClient.ErrorType;
import com.cakeandcandles.party.InvitationClient.Dispatch;
import com.cakeandcandles.party.PartyExceptions.CakeRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The distinction under test: a bakery that is broken is a failed party (502), a request the
 * bakery correctly refused is a bad request (400). Collapsing the two means a caller asking
 * for an impossible cake shows up in the same numbers as an outage.
 */
class PartyPlannerTest {

    private static final List<String> GUESTS = List.of("a@example.com");

    private CakeClient cakes;
    private InvitationClient invitations;
    private PartyPlanner planner;

    @BeforeEach
    void setUp() {
        cakes = mock(CakeClient.class);
        invitations = mock(InvitationClient.class);
        planner = new PartyPlanner(cakes, invitations,
                new CakeFlavors(Set.of("chocolate", "vanilla")));
    }

    private PartyRequest request() {
        return new PartyRequest("Ada", LocalDate.of(1990, 5, 1), "chocolate", GUESTS);
    }

    private void invitationsSucceed() {
        when(invitations.send(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new InvitationClient.Result(Dispatch.DELIVERED, null, GUESTS.size(), 0, List.of()));
    }

    @Test
    @DisplayName("a rejected order is a bad request, not a failed party")
    void rejectedOrderIsABadRequest() {
        when(cakes.order(anyString(), anyString(), anyInt()))
                .thenThrow(new CakeException(ErrorType.REJECTED, "400 too many candles", null));

        assertThatThrownBy(() -> planner.plan(request()))
                .isInstanceOf(CakeRejectedException.class);
    }

    @Test
    @DisplayName("a rejected order stores no party")
    void rejectedOrderStoresNoParty() {
        // A party that was never valid should leave nothing behind to list or look up.
        when(cakes.order(anyString(), anyString(), anyInt()))
                .thenThrow(new CakeException(ErrorType.REJECTED, "400 too many candles", null));

        assertThatThrownBy(() -> planner.plan(request())).isInstanceOf(CakeRejectedException.class);

        assertThat(planner.all()).isEmpty();
    }

    @Test
    @DisplayName("a bakery failure is still a FAILED party, not a bad request")
    void bakeryFailureIsAFailedParty() {
        when(cakes.order(anyString(), anyString(), anyInt()))
                .thenThrow(new CakeException(ErrorType.OUT_OF_STOCK, "503 out of stock", null));

        Party party = planner.plan(request());

        assertThat(party.status()).isEqualTo(Party.Status.FAILED);
        assertThat(party.failureReason()).contains("out of stock");
        assertThat(planner.all()).hasSize(1);
    }

    @Test
    @DisplayName("an unreachable bakery is a FAILED party too")
    void unreachableBakeryIsAFailedParty() {
        when(cakes.order(anyString(), anyString(), anyInt()))
                .thenThrow(new CakeException(ErrorType.UNREACHABLE, "connection refused", null));

        assertThat(planner.plan(request()).status()).isEqualTo(Party.Status.FAILED);
    }

    @Test
    @DisplayName("a flavor the bakery does not serve never reaches the bakery")
    void unknownFlavorIsRejectedUpFront() {
        PartyRequest unicorn = new PartyRequest("Ada", LocalDate.of(1990, 5, 1), "unicorn", GUESTS);

        assertThatThrownBy(() -> planner.plan(unicorn))
                .isInstanceOf(PartyExceptions.UnknownFlavorException.class);
    }

    @Test
    @DisplayName("a successful order plans the party")
    void successfulOrderPlansTheParty() {
        when(cakes.order(anyString(), anyString(), anyInt()))
                .thenReturn(new CakeClient.Cake("cake-1", "chocolate", 36, 400));
        invitationsSucceed();

        Party party = planner.plan(request());

        assertThat(party.status()).isEqualTo(Party.Status.PLANNED);
        assertThat(party.cakeId()).isEqualTo("cake-1");
        assertThat(party.failureReason()).isNull();
    }

    @Test
    @DisplayName("an invitation outage leaves the party PARTIAL, with a reason")
    void invitationOutageIsPartialWithAReason() {
        when(cakes.order(anyString(), anyString(), anyInt()))
                .thenReturn(new CakeClient.Cake("cake-1", "chocolate", 36, 400));
        when(invitations.send(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new InvitationClient.Result(Dispatch.UNAVAILABLE,
                        InvitationClient.ErrorType.UNREACHABLE, 0, GUESTS.size(), GUESTS));

        Party party = planner.plan(request());

        assertThat(party.status()).isEqualTo(Party.Status.PARTIAL);
        assertThat(party.failureReason()).contains("invitation-service unavailable");
    }
}
