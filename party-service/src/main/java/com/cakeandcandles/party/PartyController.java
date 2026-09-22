package com.cakeandcandles.party;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

@RestController
@RequestMapping("/parties")
public class PartyController {

    private final PartyPlanner planner;

    public PartyController(PartyPlanner planner) {
        this.planner = planner;
    }

    @PostMapping
    public ResponseEntity<Party> plan(@Valid @RequestBody PartyRequest request) {
        Party party = planner.plan(request);
        HttpStatus status = switch (party.status()) {
            case PLANNED, PARTIAL -> HttpStatus.CREATED;
            case FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(party);
    }

    @GetMapping
    public Collection<Party> list() {
        return planner.all();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Party> get(@PathVariable String id) {
        return ResponseEntity.of(planner.find(id));
    }
}
