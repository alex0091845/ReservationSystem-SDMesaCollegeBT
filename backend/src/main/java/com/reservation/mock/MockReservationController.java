package com.reservation.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.controller.ReservationController;
import com.reservation.repositories.ReservationRepository;
import com.reservation.services.FileService;

@Profile("mock")
@RestController
@RequestMapping("/api/events")
public class MockReservationController extends ReservationController {

    private final FileService fileService;
    private final ReservationRepository repository;

    public MockReservationController() {
        super(null);

        fileService = new FileService();
        repository = new ReservationRepository(fileService);
    }

    @Override
    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(repository.getAllEvents());
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable int id) {
        return ResponseEntity.ok(repository.getEventById(id));
    }

    // @GetMapping("/by-user/{userId}")
    // public ResponseEntity<String> getByUser(@PathVariable int userId) {
    //     return ResponseEntity.ok(supabase.get("events?user_id=eq." + userId + "&select=*,users(first_name,last_name)"));
    // }

    // @GetMapping("/public")
    // public ResponseEntity<String> getPublic() {
    //     return ResponseEntity.ok(supabase.get("events?is_public=eq.true&select=*,users(first_name,last_name)"));
    // }

    // @PostMapping
    // public ResponseEntity<String> create(@RequestBody String body) {
    //     return ResponseEntity.ok(supabase.post("events", body));
    // }

    // @PatchMapping("/{id}")
    // public ResponseEntity<String> update(@PathVariable int id, @RequestBody String body) {
    //     return ResponseEntity.ok(supabase.patch("events?id=eq." + id, body));
    // }

    // @DeleteMapping("/{id}")
    // public ResponseEntity<Void> delete(@PathVariable int id) {
    //     supabase.delete("events?id=eq." + id);
    //     return ResponseEntity.noContent().build();
    // }
}
