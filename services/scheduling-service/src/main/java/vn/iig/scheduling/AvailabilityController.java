package vn.iig.scheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.iig.scheduling.SchedulingModels.*;

/** Identical browser API paths and shapes to the legacy SchedulingController. */
@RestController
@RequestMapping("/api/availabilities")
public class AvailabilityController {
    private final AvailabilityService service;
    public AvailabilityController(AvailabilityService service) {this.service=service;}

    @GetMapping("/me")
    public List<Map<String,Object>> mine(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam(value="weekStart",required=false) LocalDate week) {
        return service.mine(bearer,week);
    }

    @GetMapping
    public List<Map<String,Object>> list(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam(value="role",required=false) String role,
            @RequestParam(value="status",required=false) String status,
            @RequestParam(value="weekStart",required=false) LocalDate week) {
        return service.list(bearer,role,status,week);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @Valid @RequestBody AvailabilityRequest body) {
        return service.create(bearer,body);
    }

    @PutMapping("/{id}")
    public Map<String,Object> update(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String id,@Valid @RequestBody AvailabilityRequest body) {
        return service.update(bearer,id,body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value="Authorization",required=false) String bearer,
                       @PathVariable String id) {
        service.delete(bearer,id);
    }

    @PatchMapping("/{id}/review")
    public Map<String,Object> review(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String id,@Valid @RequestBody ReviewRequest body) {
        return service.review(bearer,id,body);
    }
}
