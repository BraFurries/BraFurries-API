package com.Brafurries.API.platform.drive;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/downloads")
public class GoogleDriveStreamingDownloadController {
    private final GoogleDriveStreamingDownloadService service;
    public GoogleDriveStreamingDownloadController(GoogleDriveStreamingDownloadService service){this.service=service;}
    public record Capability(boolean enabled) {}
    public record Request(String anchor,String folder,String file) {}
    @GetMapping("/capability")
    public ResponseEntity<Capability> capability(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Capability(service.enabled()));
    }
    @PostMapping("/ticket")
    public ResponseEntity<GoogleDriveDownloadTicketService.Ticket> issue(Authentication auth,@RequestBody Request request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issue(auth,request.anchor(),request.folder(),request.file()));
    }
    // Exact unauthenticated transport endpoint: one-use, 60s bearer in POST body only.
    @PostMapping(value="/redeem",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void redeem(@RequestParam("ticket") String ticket,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","no-store");
        service.download(ticket,response);
    }
}
