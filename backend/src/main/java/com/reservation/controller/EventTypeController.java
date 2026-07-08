package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/event-types")
public class EventTypeController {

    private final SupabaseClient supabase;
    public EventTypeController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("event_types?select=*"));
    }

    @GetMapping("/{eventType}")
    public ResponseEntity<String> getByType(@PathVariable String eventType) {
        return ResponseEntity.ok(supabase.get("event_types?event_type=eq." + eventType));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return ResponseEntity.ok(supabase.post("event_types", body));
    }

    @PatchMapping("/{eventType}")
    public ResponseEntity<String> update(@PathVariable String eventType, @RequestBody String body) {
        return ResponseEntity.ok(supabase.patch("event_types?event_type=eq." + eventType, body));
    }

    @DeleteMapping("/{eventType}")
    public ResponseEntity<Void> delete(@PathVariable String eventType) {
        supabase.delete("event_types?event_type=eq." + eventType);
        return ResponseEntity.noContent().build();
    }
}
