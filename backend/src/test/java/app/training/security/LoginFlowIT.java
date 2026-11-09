package app.training.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The real browser flow: CSRF cookie, then login, then session. Runs in its own
 * application context because spring-security-test's csrf() post-processor
 * (used in other tests) permanently swaps the cookie repository for a stub.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "test.context=login-flow")
class LoginFlowIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test
    void csrfCookieThenLoginThenSession() throws Exception {
        String username = "coach" + System.nanoTime();
        users.insert(username, encoder.encode("coach-password-1"), Role.COACH, null);
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}";

        Cookie xsrf = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();

        // No header echoing the cookie: refused as CSRF.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(xsrf)
                        .content(body.formatted(username, "coach-password-1")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .content(body.formatted(username, "wrong")))
                .andExpect(status().isUnauthorized());

        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .content(body.formatted(username, "coach-password-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("COACH"))
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.username").value(username));
        mvc.perform(get("/api/athletes").session(session)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }
}
