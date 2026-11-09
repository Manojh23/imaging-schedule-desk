package dev.scheduledesk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ScheduleController {
    private final ScheduleService service;
    public ScheduleController(ScheduleService service) { this.service=service; }
    public record Slot(@NotBlank @Size(max=12) String scannerId,@NotNull OffsetDateTime startsAt,
                       @NotNull @Min(15) @Max(120) Integer durationMinutes) {}
    public record Create(@NotNull UUID requestKey,@NotBlank @Pattern(regexp="DEMO-[A-Z0-9]{1,12}") String demoRef,
                         @Valid @NotNull Slot slot) {}
    public record Move(@NotNull @Min(0) Integer version,@Valid @NotNull Slot slot) {}
    public record Cancel(@NotNull @Min(0) Integer version) {}

    @GetMapping("/scanners")
    List<ScheduleService.Scanner> scanners() { return service.scanners(); }
    @GetMapping("/appointments")
    List<ScheduleService.Appointment> list(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) { return service.day(date); }
    @GetMapping("/appointments/{id}/events")
    List<ScheduleService.Event> history(@PathVariable UUID id) { return service.history(id); }

    @PostMapping("/appointments")
    ResponseEntity<ScheduleService.Appointment> book(@RequestBody @Valid Create req) {
        var s=req.slot();
        var b=service.book(req.requestKey(),s.scannerId(),req.demoRef(),s.startsAt(),s.durationMinutes());
        return ResponseEntity.status(b.replay()?HttpStatus.OK:HttpStatus.CREATED).body(b.appointment());
    }
    @PatchMapping("/appointments/{id}")
    ScheduleService.Appointment move(@PathVariable UUID id,@RequestBody @Valid Move req) {
        var s=req.slot();
        return service.move(id,req.version(),s.scannerId(),s.startsAt(),s.durationMinutes());
    }
    @PostMapping("/appointments/{id}/cancel")
    ScheduleService.Appointment cancel(@PathVariable UUID id,@RequestBody @Valid Cancel req) {
        return service.cancel(id,req.version());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"Request rejected":e.getReason()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,String>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error","Enter a DEMO reference and a valid scanner, date, and duration"));
    }
}
