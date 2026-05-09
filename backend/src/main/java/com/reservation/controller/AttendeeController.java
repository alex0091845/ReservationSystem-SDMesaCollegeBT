package com.reservation.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.config.SupabaseClient;

@Profile("!mock")
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
