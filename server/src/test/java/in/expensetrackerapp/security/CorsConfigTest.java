package in.expensetrackerapp.security;

import in.expensetrackerapp.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private CorsConfiguration configFor(String origins) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        CorsConfigurationSource source = config.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/transactions"));
    }

    @Test
    void onlyTheConfiguredOriginsAreAllowedAndNeverAWildcard() {
        CorsConfiguration cors = configFor(" https://app.finpulse.example , https://admin.finpulse.example ,, ");

        assertThat(cors.getAllowedOrigins()).containsExactly("https://app.finpulse.example", "https://admin.finpulse.example");
        assertThat(cors.getAllowedOrigins()).doesNotContain("*");
    }

    @Test
    void credentialedCorsIsOffBecauseTheApiUsesBearerTokensNotCookies() {
        assertThat(configFor("https://app.finpulse.example").getAllowCredentials()).isFalse();
    }

    @Test
    void methodsAndHeadersAreLimitedToWhatTheApiNeeds() {
        CorsConfiguration cors = configFor("https://app.finpulse.example");

        assertThat(cors.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(cors.getAllowedHeaders()).containsExactlyInAnyOrder("Authorization", "Content-Type", "Accept", "X-Requested-With", "Origin");
        assertThat(cors.getAllowedHeaders()).doesNotContain("*");
    }

    @Test
    void aMissingConfigurationAllowsNoOriginInsteadOfEverything() {
        assertThat(configFor("").getAllowedOrigins()).isEmpty();
    }
}
