package com.reservation.controller;

import com.reservation.config.SupabaseClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final SupabaseClient supabase;
    public UserController(SupabaseClient supabase) { this.supabase = supabase; }

    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(supabase.get("users?select=*,user_roles(name)"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(supabase.get("users?id=eq." + id + "&select=*,user_roles(name)"));
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestBody String body) {
        return ResponseEntity.ok(supabase.post("users", body));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
        return ResponseEntity.ok(supabase.patch("users?id=eq." + id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        supabase.delete("users?id=eq." + id);
        return ResponseEntity.noContent().build();
    }
}
