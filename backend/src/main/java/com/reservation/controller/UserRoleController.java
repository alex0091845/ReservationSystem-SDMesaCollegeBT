package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/roles")
public class UserRoleController {

    private final SupabaseClient supabase;
    public UserRoleController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("user_roles?select=*"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(supabase.get("user_roles?id=eq." + id));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return ResponseEntity.ok(supabase.post("user_roles", body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        return ResponseEntity.ok(supabase.patch("user_roles?id=eq." + id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        supabase.delete("user_roles?id=eq." + id);
        return ResponseEntity.noContent().build();
    }
}
