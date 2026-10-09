package com.Brafurries.API.context;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("")
@Tag(name = "Contexto", description = "Endpoints de validação de contexto e permissões")
public class ContextController {

    @Operation(summary = "Ping público")
    @GetMapping("/public/ping")
    public Map<String, String> publicContext() {
        return Map.of("context", "public", "message", "Endpoint público acessível por qualquer pessoa");
    }

    @Operation(summary = "Ping autenticado de usuário")
    @GetMapping("/user/ping")
    public Map<String, String> userContext(Authentication authentication) {
        return Map.of(
            "context", "user",
            "message", "Endpoint para usuários autenticados",
            "authenticated_as", authentication.getName()
        );
    }

    @Operation(summary = "Ping autenticado third-party")
    @GetMapping("/third-party/ping")
    public Map<String, String> thirdPartyContext(Authentication authentication) {
        return Map.of(
            "context", "third-party",
            "message", "Endpoint para integrações de terceiros autenticadas",
            "client", authentication.getName()
        );
    }

    @Operation(summary = "Ping autenticado de administrador")
    @GetMapping("/admin/ping")
    public Map<String, String> adminContext(Authentication authentication) {
        return Map.of(
            "context", "admin",
            "message", "Endpoint exclusivo para administradores",
            "administrator", authentication.getName()
        );
    }
}
