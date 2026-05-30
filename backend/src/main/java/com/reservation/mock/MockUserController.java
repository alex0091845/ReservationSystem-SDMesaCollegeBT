package com.reservation.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.controller.UserController;
import com.reservation.repositories.UserRepository;
import com.reservation.services.FileService;

@Profile("mock")
@RestController
@RequestMapping("/api/users")
public class MockUserController extends UserController {

    private final FileService fileService;
    private final UserRepository repository;

    public MockUserController() {
        super(null);

        fileService = new FileService();
        repository = new UserRepository(fileService);
    }

    @Override
    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(repository.getAllUsers());
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(repository.getUserById(id));
    }

    // @PostMapping
    // public ResponseEntity<String> create(@RequestBody String body) {
    //     return ResponseEntity.ok(supabase.post("users", body));
    // }

    // @PatchMapping("/{id}")
    // public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
    //     return ResponseEntity.ok(supabase.patch("users?id=eq." + id, body));
    // }

    // @DeleteMapping("/{id}")
    // public ResponseEntity<Void> delete(@PathVariable int id) {
    //     supabase.delete("users?id=eq." + id);
    //     return ResponseEntity.noContent().build();
    // }
}
