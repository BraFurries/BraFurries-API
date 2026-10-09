package com.Brafurries.API.config;

import com.Brafurries.API.auth.social.SocialFrontendOriginPolicy;
import com.Brafurries.API.auth.social.SocialOAuth2AuthenticationSuccessHandler;
import com.Brafurries.API.common.dto.ApiErrorResponse;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;
    private final ApiUserRoleRepository apiUserRoleRepository;
    private final ApiRolePermissionRepository apiRolePermissionRepository;
    private final SocialOAuth2AuthenticationSuccessHandler socialOAuth2AuthenticationSuccessHandler;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
        BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
        UserRepository userRepository,
        UserTokenRepository userTokenRepository,
        ApiUserRoleRepository apiUserRoleRepository,
        ApiRolePermissionRepository apiRolePermissionRepository,
        SocialOAuth2AuthenticationSuccessHandler socialOAuth2AuthenticationSuccessHandler,
        ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository,
        ObjectMapper objectMapper
    ) {
        this.bearerTokenAuthenticationFilter = bearerTokenAuthenticationFilter;
        this.userRepository = userRepository;
        this.userTokenRepository = userTokenRepository;
        this.apiUserRoleRepository = apiUserRoleRepository;
        this.apiRolePermissionRepository = apiRolePermissionRepository;
        this.socialOAuth2AuthenticationSuccessHandler = socialOAuth2AuthenticationSuccessHandler;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.objectMapper = objectMapper;
    }

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${springdoc.swagger-ui.path:/swagger-ui}")
    private String swaggerUiPath;

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String openApiDocsPath;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/auth/**", "/oauth/token").permitAll()
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/meta/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/internal/identity/**").permitAll()
                .requestMatchers("/internal/community-networks/**").permitAll()
                .requestMatchers("/internal/themes/**").permitAll()
                .requestMatchers("/internal/backup-progress").permitAll()
                .requestMatchers(HttpMethod.GET, "/invites/event-transfer/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/events/public").permitAll()
                .requestMatchers(new RegexRequestMatcher("^/events/\\d+$", "GET")).permitAll()
                .requestMatchers(swaggerUiMatcher(), openApiDocsMatcher(), openApiDocsYamlMatcher()).hasAnyRole("ADMIN", "DEVELOPER")
                .requestMatchers("/user/**").authenticated()
                .requestMatchers("/events/**").authenticated()
                .requestMatchers("/third-party/**").hasAnyAuthority("CLIENT_PARTNER", "CLIENT_BOT", "CLIENT_SERVICE", "CLIENT_INTERNAL")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    if (docsRequestMatcher().matches(request)) {
                        response.setHeader("WWW-Authenticate", "Basic realm=\"BraFurries API\"");
                    }
                    writeJsonError(response, HttpStatus.UNAUTHORIZED, "Não autenticado ou token inválido", request.getRequestURI());
                })
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    writeJsonError(response, HttpStatus.FORBIDDEN, "Sem permissão para acessar este recurso", request.getRequestURI())
                )
            );

        if (clientRegistrationRepository.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                .loginPage("/auth/oauth2/discord")
                .successHandler(socialOAuth2AuthenticationSuccessHandler)
                .failureHandler(socialOAuth2AuthenticationSuccessHandler)
            );
        }

        http.httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(parseAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private RequestMatcher docsRequestMatcher() {
        return new OrRequestMatcher(
            new AntPathRequestMatcher(swaggerUiMatcher()),
            new AntPathRequestMatcher(openApiDocsMatcher()),
            new AntPathRequestMatcher(openApiDocsYamlMatcher())
        );
    }

    private String swaggerUiMatcher() {
        return toAntMatcher(swaggerUiPath);
    }

    private String openApiDocsMatcher() {
        return toAntMatcher(openApiDocsPath);
    }

    private String openApiDocsYamlMatcher() {
        return toYamlMatcher(openApiDocsPath);
    }

    private String toAntMatcher(String basePath) {
        String normalized = normalizeBasePath(basePath);
        return normalized.endsWith("/**") ? normalized : normalized + "/**";
    }

    private String toYamlMatcher(String basePath) {
        String normalized = normalizeBasePath(basePath);
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + ".yaml";
    }

    private String normalizeBasePath(String basePath) {
        String normalized = basePath == null || basePath.isBlank() ? "/" : basePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private List<String> parseAllowedOrigins() {
        return Stream.concat(
                Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty()),
                Stream.of(SocialFrontendOriginPolicy.FIREBASE_PREVIEW_ORIGIN_PATTERN)
            )
            .distinct()
            .toList();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            String normalizedUsername = username.trim().toLowerCase();
            var user = userRepository.findByEmail(normalizedUsername)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("Usuário não encontrado"));

            String passwordHash = user.getPasswordHash();
            if (passwordHash == null || passwordHash.isBlank()) {
                throw new org.springframework.security.authentication.BadCredentialsException("Usuário sem senha local cadastrada");
            }

            if (userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
                    user,
                    UserTokenType.EMAIL_VERIFICATION
            )) {
                throw new org.springframework.security.authentication.BadCredentialsException("Confirme seu e-mail antes de acessar a conta");
            }

            var roles = apiUserRoleRepository.findRoleNamesByUserId(user.getId());
            var permissions = apiRolePermissionRepository.findPermissionNamesByUserId(user.getId());

            var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(java.util.Locale.ROOT))));
            permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

            return new org.springframework.security.core.userdetails.User(normalizedUsername, passwordHash, authorities);
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
            Instant.now(),
            status.value(),
            status.getReasonPhrase(),
            message,
            path,
            null
        ));
    }
}
