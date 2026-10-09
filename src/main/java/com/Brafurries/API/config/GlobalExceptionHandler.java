package com.Brafurries.API.config;

import com.Brafurries.API.common.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (first, second) -> first));

        return ResponseEntity.badRequest().body(buildBody(HttpStatus.BAD_REQUEST, "Payload inválido", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(buildBody(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido", request.getRequestURI(), null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(buildBody(HttpStatus.BAD_REQUEST, "Parâmetro obrigatório ausente: " + ex.getParameterName(), request.getRequestURI(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(buildBody(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), null));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(buildBody(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI(), null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(buildBody(HttpStatus.FORBIDDEN, "Sem permissão para acessar este recurso", request.getRequestURI(), null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatusCode())
            .body(buildBody(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), request.getRequestURI(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), resolveSafeRoute(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildBody(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor", request.getRequestURI(), null));
    }

    static String resolveSafeRoute(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route && !route.isBlank() ? route : "<unresolved-route>";
    }

    private ApiErrorResponse buildBody(HttpStatus status, String message, String path, Map<String, String> details) {
        return new ApiErrorResponse(
            Instant.now(),
            status.value(),
            status.getReasonPhrase(),
            message,
            path,
            details != null && !details.isEmpty() ? details : null
        );
    }
}
