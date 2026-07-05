package com.reservation.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.controller.EventTypeController;
import com.reservation.repositories.EventTypeRepository;
import com.reservation.services.FileService;

@Profile("mock")
@RestController
@RequestMapping("/api/event-types")
public class MockEventTypeController extends EventTypeController {

    private final FileService fileService;
    private final EventTypeRepository repository;

    public MockEventTypeController() {
        super(null);

        fileService = new FileService();
        repository = new EventTypeRepository(fileService);
    }

    @Override
    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(repository.getAllEventTypes());
    }

    @Override
    @GetMapping("/{eventType}")
    public ResponseEntity<String> getByType(@PathVariable String eventType) {
        return ResponseEntity.ok(repository.getEventTypeByType(eventType));
    }

    // @PostMapping
    // public ResponseEntity<String> create(@RequestBody String body) {
    //     return ResponseEntity.ok(supabase.post("event_types", body));
    // }

    // @PatchMapping("/{eventType}")
    // public ResponseEntity<String> update(@PathVariable String eventType, @RequestBody String body) {
    //     return ResponseEntity.ok(supabase.patch("event_types?event_type=eq." + eventType, body));
    // }

    // @DeleteMapping("/{eventType}")
    // public ResponseEntity<Void> delete(@PathVariable String eventType) {
    //     supabase.delete("event_types?event_type=eq." + eventType);
    //     return ResponseEntity.noContent().build();
    // }
}
