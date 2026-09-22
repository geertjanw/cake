package com.cakeandcandles.cake;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class CakeController {

    private final Bakery bakery;

    public CakeController(Bakery bakery) {
        this.bakery = bakery;
    }

    /** Bake a cake. This is the endpoint party-service calls. */
    @PostMapping("/cakes")
    @ResponseStatus(HttpStatus.CREATED)
    public CakeResponse bake(@Valid @RequestBody CakeRequest request) {
        return bakery.bake(request);
    }

    /** Current flavor inventory - handy to see why orders start failing. */
    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory() {
        return bakery.inventory();
    }

    /** Restock a flavor so orders succeed again. */
    @PostMapping("/inventory/{flavor}/restock")
    public Map<String, Object> restock(@PathVariable String flavor,
                                       @RequestParam(defaultValue = "10") int amount) {
        return bakery.restock(flavor, amount);
    }
}
