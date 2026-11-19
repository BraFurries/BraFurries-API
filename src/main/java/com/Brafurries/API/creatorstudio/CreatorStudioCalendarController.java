package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioCalendarDtos.*;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/creator-studio")
public class CreatorStudioCalendarController {
    private final CreatorStudioCalendarService calendar;
    public CreatorStudioCalendarController(CreatorStudioCalendarService calendar) {
        this.calendar = calendar;
    }

    @GetMapping("/calendar")
    public ResponseEntity<Page> calendar(Authentication auth,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="100") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(calendar.calendar(auth,start,end,page,pageSize));
    }

    @GetMapping("/calendar/unplanned")
    public ResponseEntity<Page> unplanned(Authentication auth,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="40") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(calendar.unplanned(auth,page,pageSize));
    }

    @GetMapping("/commissions/{id}/planning")
    public ResponseEntity<List<Item>> planning(Authentication auth,@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendar.commission(auth,id));
    }

    @PutMapping("/commissions/{id}/planning")
    public ResponseEntity<Void> plan(Authentication auth,@PathVariable long id,@RequestBody Plan input) {
        calendar.plan(auth,id,null,input);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping("/commissions/{id}/steps/{stepId}/planning")
    public ResponseEntity<Void> planStep(Authentication auth,@PathVariable long id,@PathVariable long stepId,
            @RequestBody Plan input) {
        calendar.plan(auth,id,stepId,input);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
