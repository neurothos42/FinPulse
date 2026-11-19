package in.expensetrackerapp.security;

import in.expensetrackerapp.auth.security.JwtAuthenticationEntryPoint;
import in.expensetrackerapp.auth.security.JwtAuthenticationFilter;
import in.expensetrackerapp.auth.security.JwtService;
import in.expensetrackerapp.auth.security.SecurityConfig;
import in.expensetrackerapp.common.exception.GlobalExceptionHandler;
import in.expensetrackerapp.config.CorsConfig;
import in.expensetrackerapp.document.controller.DocumentController;
import in.expensetrackerapp.document.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Headers and CORS behaviour of the real security filter chain. */
@WebMvcTest(controllers = DocumentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        CorsConfig.class, GlobalExceptionHandler.class})
class SecurityHeadersTest {

    private static final String TOKEN = "valid.jwt.token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void authenticatedApiResponsesCarryAntiSniffingFramingAndNoCacheHeaders() throws Exception {
        when(jwtService.extractUserId(TOKEN)).thenReturn("user-1");
        when(jwtService.isTokenValid(TOKEN, "user-1")).thenReturn(true);
        when(documentService.list("user-1", 0, 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/documents").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void rejectedRequestsAreNotCacheableEither() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void anUnlistedOriginGetsNoCorsApproval() throws Exception {
        mockMvc.perform(options("/api/documents")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void aListedOriginIsApprovedWithoutCredentials() throws Exception {
        mockMvc.perform(options("/api/documents")
                        .header("Origin", "http://localhost:8081")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
