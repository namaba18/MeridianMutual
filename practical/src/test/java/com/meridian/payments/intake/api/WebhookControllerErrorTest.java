package com.meridian.payments.intake.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.meridian.payments.intake.core.IntakeService;

@ExtendWith(OutputCaptureExtension.class)
class WebhookControllerErrorTest {

    @Test
    void aDefectInOurCodeIsA500NeverAnAcceptanceAndLeaksNothing(CapturedOutput output) throws Exception {
        IntakeService broken = mock(IntakeService.class);
        when(broken.handle(any(), any()))
                .thenThrow(new IllegalStateException("bug while reading laura.gomez@example.com"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WebhookController(broken)).build();

        mvc.perform(post("/v1/webhooks/andina").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError()) // the provider retries; nothing was accepted
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("laura.gomez@example.com"))));

        assertThat(output.getAll())
                .contains("Unexpected intake error type=java.lang.IllegalStateException")
                .doesNotContain("laura.gomez@example.com");
    }
}
