package app.training.garmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.training.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

/** ADR 0011 D10: without a valid key or secret, sync answers "not configured" and nothing reaches the sidecar. */
@ExtendWith(OutputCaptureExtension.class)
class GarminNotConfiguredTest {

    @Test
    void connectRefusedStatusSaysSoAndDisconnectStillWorks(CapturedOutput output) {
        var props = new GarminProperties("too-short", "test-sync-secret", "http://127.0.0.1:1", 1);
        var setup = new GarminSetup(props);
        var repo = mock(GarminCredentialRepository.class);
        var sidecar = mock(SidecarClient.class);
        var service = new GarminConnectionService(setup, repo, sidecar, Clock.systemUTC());

        assertThat(setup.enabled()).isFalse();
        assertThat(output.getAll()).contains("Garmin sync disabled: GARMIN_TOKEN_KEY").doesNotContain("too-short")
                .doesNotContain("test-sync-secret");

        when(repo.find(1L)).thenReturn(Optional.empty());
        assertThat(service.status(1L).state()).isEqualTo(GarminConnectionService.State.NOT_CONFIGURED);
        assertThat(service.status(1L).stored()).isFalse();

        when(repo.find(2L)).thenReturn(Optional.of(new GarminCredentialRepository.Row(2L, new byte[1], "deadbeef",
                "CONNECTED", Instant.now(), null, null)));
        assertThat(service.status(2L).stored()).isTrue();

        assertThatThrownBy(() -> service.connect(1L, "test-user", "test-password"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("NOT_CONFIGURED");
                });
        assertThatThrownBy(() -> service.mfa(1L, "x", "1")).isInstanceOf(ApiException.class);
        verifyNoInteractions(sidecar);

        service.disconnect(2L);
        org.mockito.Mockito.verify(repo).delete(2L);
    }

    @Test
    void missingSecretAlsoDisables() {
        byte[] k = new byte[32];
        var setup = new GarminSetup(new GarminProperties(java.util.Base64.getEncoder().encodeToString(k), "", "x", 1));
        assertThat(setup.enabled()).isFalse();
        assertThat(setup.disabledReason()).contains("GARMIN_SYNC_SECRET");
    }
}
