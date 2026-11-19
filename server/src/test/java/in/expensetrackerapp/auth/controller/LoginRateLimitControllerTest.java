package in.expensetrackerapp.auth.controller;

import in.expensetrackerapp.auth.security.JwtAuthenticationEntryPoint;
import in.expensetrackerapp.auth.security.JwtAuthenticationFilter;
import in.expensetrackerapp.auth.security.JwtService;
import in.expensetrackerapp.auth.security.SecurityConfig;
import in.expensetrackerapp.auth.service.AuthService;
import in.expensetrackerapp.common.exception.GlobalExceptionHandler;
import in.expensetrackerapp.common.exception.TooManyRequestsException;
import in.expensetrackerapp.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        CorsConfig.class, GlobalExceptionHandler.class})
class LoginRateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void aLockedAccountGets429WithRetryAfterAndNoDetailsAboutTheAccount() throws Exception {
        when(authService.login(any())).thenThrow(new TooManyRequestsException("Too many failed login attempts. Try again later.", 840));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Password123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Too many failed login attempts. Try again later."));
    }
}
