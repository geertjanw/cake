package com.cakeandcandles.party;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class InvitationClient {

    private static final Logger log = LoggerFactory.getLogger(InvitationClient.class);

    public record Result(int sent, int failed, List<String> failedGuests) {}

    private final RestClient http;

    public InvitationClient(RestClient.Builder builder, @Value("${clients.invitation.url}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public Result send(String partyId, String hostName, int age, List<String> guests) {
        try {
            return http.post().uri("/invitations")
                    .body(Map.of("partyId", partyId, "hostName", hostName, "age", age, "guests", guests))
                    .retrieve().body(Result.class);
        } catch (RestClientException e) {
            // Invitations are best-effort: log, and report everything as failed.
            log.error("invitation-service failed for party {}: {}", partyId, e.getMessage());
            return new Result(0, guests.size(), guests);
        }
    }
}
