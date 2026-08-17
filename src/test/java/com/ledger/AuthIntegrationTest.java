package com.ledger;

import com.ledger.dto.AuthResponse;
import com.ledger.dto.UserDto;
import com.ledger.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends AbstractIntegrationTest {

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void registerLoginRefreshAndReuseDetection() {
        String email = "user-" + UUID.randomUUID() + "@test.com";

        ResponseEntity<AuthResponse> reg = rest.postForEntity(baseUrl(port) + "/api/auth/register",
                Map.of("email", email, "password", "secret123", "firstName", "Test", "lastName", "User"),
                AuthResponse.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reg.getBody()).isNotNull();
        assertThat(reg.getBody().accessToken()).isNotBlank();
        assertThat(reg.getBody().refreshToken()).isNotBlank();

        // me with access token
        ResponseEntity<UserDto> me = rest.exchange(baseUrl(port) + "/api/auth/me", HttpMethod.GET,
                bearer(reg.getBody().accessToken()), UserDto.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().email()).isEqualTo(email);

        // refresh rotates the token
        ResponseEntity<AuthResponse> refreshed = rest.postForEntity(baseUrl(port) + "/api/auth/refresh",
                Map.of("refreshToken", reg.getBody().refreshToken()), AuthResponse.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(reg.getBody().refreshToken());

        // reusing the revoked token is detected -> 401
        ResponseEntity<String> reuse = rest.postForEntity(baseUrl(port) + "/api/auth/refresh",
                Map.of("refreshToken", reg.getBody().refreshToken()), String.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithWrongPasswordRejected() {
        createUser("badpass-" + UUID.randomUUID() + "@test.com");
        ResponseEntity<String> login = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                Map.of("email", "missing@test.com", "password", "wrong"), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
