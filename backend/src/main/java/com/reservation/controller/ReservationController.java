package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class ReservationController {

    private final SupabaseClient supabase;
    public ReservationController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("events?select=*,users(first_name,last_name)"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(supabase.get("events?id=eq." + id + "&select=*,users(first_name,last_name)"));
    }

    @GetMapping("/by-user/{userId}")
    public ResponseEntity<String> getByUser(@PathVariable int userId) {
        return ResponseEntity.ok(supabase.get("events?user_id=eq." + userId + "&select=*,users(first_name,last_name)"));
    }

    @GetMapping("/public")
    public ResponseEntity<String> getPublic() {
        return ResponseEntity.ok(supabase.get("events?is_public=eq.true&select=*,users(first_name,last_name)"));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return ResponseEntity.ok(supabase.post("events", body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        return ResponseEntity.ok(supabase.patch("events?id=eq." + id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        supabase.delete("events?id=eq." + id);
        return ResponseEntity.noContent().build();
    }
}
