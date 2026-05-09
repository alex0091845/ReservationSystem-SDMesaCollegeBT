package com.reservation.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reservation.controller.AttendeeController;
import com.reservation.repositories.AttendeeRepository;
import com.reservation.services.FileService;

@Profile("mock")
@RestController
@RequestMapping("/api/attendees")
public class MockAttendeeController extends AttendeeController
{
    private final FileService fileService;
    private final AttendeeRepository repository;

    public MockAttendeeController() {
        super(null);

        fileService = new FileService();
        repository = new AttendeeRepository(fileService);
     }

    @Override
    @GetMapping
    public ResponseEntity<String> getAll() {
        return ResponseEntity.ok(repository.getAllAttendees());
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<String> getById(@PathVariable long id) {
        return ResponseEntity.ok(repository.getAttendeeById(id));
    }

    // @GetMapping("/by-event/{eventId}")
    // public ResponseEntity<String> getByEvent(@PathVariable int eventId) {
    //     return ResponseEntity.ok(supabase.get("attendee?event_id=eq." + eventId + "&select=*,events(title)"));
    // }

    // @PostMapping
    // public ResponseEntity<String> create(@RequestBody String body) {
    //     return ResponseEntity.ok(supabase.post("attendee", body));
    // }

    // @PatchMapping("/{id}")
    // public ResponseEntity<String> update(@PathVariable long id, @RequestBody String body) {
    //     return ResponseEntity.ok(supabase.patch("attendee?id=eq." + id, body));
    // }

    // @DeleteMapping("/{id}")
    // public ResponseEntity<Void> delete(@PathVariable long id) {
    //     supabase.delete("attendee?id=eq." + id);
    //     return ResponseEntity.noContent().build();
    // }
}
