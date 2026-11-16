package app.training.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.training.IntegrationTest;
import jakarta.servlet.http.Cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import org.junit.jupiter.api.Test;
import app.training.athletes.AthleteRepository;
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
@TestPropertySource(properties = {"test.context=login-flow", "app.security.login-window=PT2S"})
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

    private Cookie xsrf() throws Exception {
        return mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private org.springframework.test.web.servlet.ResultActions attempt(String username, String password)
            throws Exception {
        Cookie x = xsrf();
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).cookie(x)
                .header("X-XSRF-TOKEN", x.getValue())
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var res = attempt(username, password).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private long athleteLogin(String username, String password) {
        long athlete = newAthlete("Runner " + username);
        return users.insert(username, encoder.encode(password), Role.ATHLETE, athlete);
    }

    @Test
    void deletedLoginStopsWorkingAtOnce() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        users.insert("coach" + suffix, encoder.encode("coach-password-1"), Role.COACH, null);
        long athleteUser = athleteLogin("runner" + suffix, "runner-password-1");
        var coach = login("coach" + suffix, "coach-password-1");
        var runner = login("runner" + suffix, "runner-password-1");
        mvc.perform(get("/api/auth/me").session(runner)).andExpect(status().isOk());

        Cookie x = xsrf();
        mvc.perform(delete("/api/users/" + athleteUser).session(coach).cookie(x).header("X-XSRF-TOKEN", x.getValue()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").session(runner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(coach)).andExpect(status().isOk());
    }

    @Test
    void passwordChangeEndsOtherSessionsButNotTheOneThatChangedIt() throws Exception {
        String name = "runner" + System.nanoTime();
        athleteLogin(name, "runner-password-1");
        var phone = login(name, "runner-password-1");
        var laptop = login(name, "runner-password-1");
        String oldId = laptop.getId();

        Cookie x = xsrf();
        mvc.perform(post("/api/users/me/password").session(laptop).contentType(MediaType.APPLICATION_JSON)
                        .cookie(x).header("X-XSRF-TOKEN", x.getValue())
                        .content("{\"currentPassword\":\"runner-password-1\",\"newPassword\":\"another-password-2\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").session(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(laptop)).andExpect(status().isOk());
        assertThat(laptop.getId()).as("session id rotated").isNotEqualTo(oldId);
        attempt(name, "runner-password-1").andExpect(status().isUnauthorized());
        attempt(name, "another-password-2").andExpect(status().isOk());
    }

    @Test
    void sixthWrongPasswordIsRejectedAndLoginWorksAfterTheWindow() throws Exception {
        String name = "runner" + System.nanoTime();
        athleteLogin(name, "runner-password-1");
        for (int i = 0; i < 5; i++) attempt(name, "wrong-" + i).andExpect(status().isUnauthorized());
        attempt(name, "wrong-5").andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists("Retry-After"));
        // Even the right password waits: otherwise the delay would not slow guessing.
        attempt(name, "runner-password-1").andExpect(status().isTooManyRequests());
        // Another username is not affected.
        String other = "runner" + System.nanoTime();
        athleteLogin(other, "runner-password-1");
        attempt(other, "runner-password-1").andExpect(status().isOk());

        Thread.sleep(2200);
        attempt(name, "runner-password-1").andExpect(status().isOk());
    }
}
