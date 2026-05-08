package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/attendee")
public class AttendeeController {

    private final SupabaseClient supabase;
    public AttendeeController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("attendee?select=*,events(title)"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable long id) {
        return ResponseEntity.ok(supabase.get("attendee?id=eq." + id + "&select=*,events(title)"));
    }

    @GetMapping("/by-event/{eventId}")
    public ResponseEntity<String> getByEvent(@PathVariable int eventId) {
        return ResponseEntity.ok(supabase.get("attendee?event_id=eq." + eventId + "&select=*,events(title)"));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return ResponseEntity.ok(supabase.post("attendee", body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable long id, @RequestBody String body) {
        return ResponseEntity.ok(supabase.patch("attendee?id=eq." + id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        supabase.delete("attendee?id=eq." + id);
        return ResponseEntity.noContent().build();
    }
}
