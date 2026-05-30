package com.reservation.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.controller.UserRoleController;
import com.reservation.repositories.UserRoleRepository;
import com.reservation.services.FileService;

@Profile("mock")
@RestController
@RequestMapping("/api/roles")
public class MockUserRoleController extends UserRoleController {

    private final FileService fileService;
    private final UserRoleRepository repository;

    public MockUserRoleController() {
        super(null);

        fileService = new FileService();
        repository = new UserRoleRepository(fileService);
    }

    @Override
    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(repository.getAllUserRoles());
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(repository.getUserRoleById(id));
    }

    // @PostMapping
    // public ResponseEntity<String> create(@RequestBody String body) {
    //     return ResponseEntity.ok(supabase.post("user_roles", body));
    // }

    // @PatchMapping("/{id}")
    // public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
    //     return ResponseEntity.ok(supabase.patch("user_roles?id=eq." + id, body));
    // }

    // @DeleteMapping("/{id}")
    // public ResponseEntity<Void> delete(@PathVariable int id) {
    //     supabase.delete("user_roles?id=eq." + id);
    //     return ResponseEntity.noContent().build();
    // }
}
