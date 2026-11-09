package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.scheduling.SchedulingModels.*;

/** Protected core-to-scheduling reservation, never forwarded by Gateway. */
@RestController
@RequestMapping("/internal/scheduling/reservations")
public class SchedulingReservationController {
    private final AvailabilityRepository store;
    private final byte[] key;

    public SchedulingReservationController(AvailabilityRepository store,
            @Value("${iig.core.internal-key}") String value) {
        if(value==null || value.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.store=store;
        key=value.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String supplied) {
        byte[] given=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,given))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid reservation key");
    }

    @PostMapping
    public Lease reserve(@RequestHeader(value="X-Internal-Key",required=false) String key,
                         @RequestBody ReservationRequest request) {
        authorize(key);
        if(!store.imported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Scheduling bootstrap pending");
        return store.reserve(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@RequestHeader(value="X-Internal-Key",required=false) String key,
                        @PathVariable String id) {
        authorize(key);
        store.release(id);
    }
}
