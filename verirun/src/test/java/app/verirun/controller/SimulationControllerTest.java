package app.verirun.controller;

import app.verirun.dto.JobStatusResponse;
import app.verirun.dto.SimulationRequest;
import app.verirun.entity.Role;
import app.verirun.security.TokenAuthenticationFilter;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.SimulationArtifactService;
import app.verirun.service.SimulationQueryService;
import app.verirun.service.SimulationSubmissionService;
import app.verirun.storage.SimulationStorageService.Output;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(value = SimulationController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TokenAuthenticationFilter.class))
@Import(SimulationControllerTest.TestSecurityConfiguration.class)
class SimulationControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvcTester mockMvcTester;

    @MockitoBean
    private SimulationSubmissionService simulationSubmissionService;

    @MockitoBean
    private SimulationQueryService simulationQueryService;

    @MockitoBean
    private SimulationArtifactService simulationArtifactService;

    private UserDetailsImpl authenticatedUser;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setName("REGISTERED_USER");

        authenticatedUser = new UserDetailsImpl(USER_ID, "user@verirun.com", "password", role);
    }

    @Test
    void runSimulation_shouldReturn200AndJobIdWhenValidRequest() {
        String validJson = """
                {
                  "designCode": "module ALU(); endmodule",
                  "testbenchCode": null
                }
                """;

        when(simulationSubmissionService.submitSimulation(any(), eq(USER_ID))).thenReturn("job-123");

        var result = assertThat(mockMvcTester.post().uri("/api/v1/simulate").contentType(MediaType.APPLICATION_JSON).content(validJson).with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.OK);
        result.bodyJson().extractingPath("$.jobId").isEqualTo("job-123");
        result.bodyJson().extractingPath("$.status").isEqualTo("PENDING");

        ArgumentCaptor<SimulationRequest> requestCaptor = ArgumentCaptor.forClass(SimulationRequest.class);

        verify(simulationSubmissionService).submitSimulation(requestCaptor.capture(), eq(USER_ID));

        assertThat(requestCaptor.getValue().designCode()).isEqualTo("module ALU(); endmodule");
        assertThat(requestCaptor.getValue().testbenchCode()).isNull();
    }

    @Test
    void runSimulation_shouldReturn400WhenDesignIsTooLarge() {
        String largeCode = "a".repeat(100_001);
        String invalidJson = String.format("""
                {
                  "designCode": "%s",
                  "testbenchCode": null
                }
                """, largeCode);

        var result = assertThat(mockMvcTester.post().uri("/api/v1/simulate").contentType(MediaType.APPLICATION_JSON).content(invalidJson).with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(simulationSubmissionService);
    }

    @Test
    void runSimulation_shouldReturn400WhenDesignIsBlank() {
        String invalidJson = """
                {
                  "designCode": "   ",
                  "testbenchCode": null
                }
                """;

        var result = assertThat(mockMvcTester.post().uri("/api/v1/simulate").contentType(MediaType.APPLICATION_JSON).content(invalidJson).with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(simulationSubmissionService);
    }

    @Test
    void runSimulation_shouldReturn400WhenTestbenchIsTooLarge() {
        String largeCode = "a".repeat(100_001);
        String invalidJson = String.format("""
                {
                  "designCode": "module ALU(); endmodule",
                  "testbenchCode": "%s"
                }
                """, largeCode);

        var result = assertThat(mockMvcTester.post().uri("/api/v1/simulate").contentType(MediaType.APPLICATION_JSON).content(invalidJson).with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(simulationSubmissionService);
    }

    @Test
    void runSimulation_shouldReturn403ForUserWithoutRegisteredUserRole() {
        Role role = new Role();
        role.setName("UNREGISTERED_USER");

        UserDetailsImpl unauthorizedUser = new UserDetailsImpl(USER_ID, "user@verirun.com", "password", role);

        String validJson = """
                {
                  "designCode": "module ALU(); endmodule",
                  "testbenchCode": null
                }
                """;

        var result = assertThat(mockMvcTester.post().uri("/api/v1/simulate").contentType(MediaType.APPLICATION_JSON).content(validJson).with(SecurityMockMvcRequestPostProcessors.user(unauthorizedUser)));

        result.hasStatus(HttpStatus.FORBIDDEN);
        verifyNoInteractions(simulationSubmissionService);
    }

    @Test
    void getJobStatus_shouldReturn200WhenStatusAvailable() {
        JobStatusResponse status = new JobStatusResponse("job-123", "RUNNING", "2026-08-24T12:00:00Z", "2026-08-24T12:00:01Z", null, null, 0, null, null);

        when(simulationQueryService.getJobStatus("job-123", USER_ID)).thenReturn(Optional.of(status));

        var result = assertThat(mockMvcTester.get().uri("/api/v1/jobs/job-123/status").with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.OK);
        result.bodyJson().extractingPath("$.jobId").isEqualTo("job-123");
        result.bodyJson().extractingPath("$.status").isEqualTo("RUNNING");

        verify(simulationQueryService).getJobStatus("job-123", USER_ID);
    }

    @Test
    void getJobStatus_shouldReturn404WhenStatusUnavailable() {
        when(simulationQueryService.getJobStatus("missing-job", USER_ID)).thenReturn(Optional.empty());

        var result = assertThat(mockMvcTester.get().uri("/api/v1/jobs/missing-job/status").with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.NOT_FOUND);

        verify(simulationQueryService).getJobStatus("missing-job", USER_ID);
    }

    @Test
    void downloadModel_shouldReturnOwnedArtifactWithDownloadHeaders() {
        byte[] model = "model bytes".getBytes(StandardCharsets.UTF_8);
        ByteArrayResource resource = new ByteArrayResource(model);

        when(simulationArtifactService.downloadArtifact("job-123", USER_ID, Output.MODEL)).thenReturn(Optional.of(resource));

        var result = assertThat(mockMvcTester.get().uri("/api/v1/simulate/download/job-123").with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.OK);
        result.satisfies(mvcResult -> {
            assertThat(mvcResult.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"verilator_model_job-123.zip\"");
            assertThat(mvcResult.getResponse().getHeader("Cache-Control")).contains("private", "max-age=3600");
            assertThat(mvcResult.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            assertThat(mvcResult.getResponse().getContentAsByteArray()).isEqualTo(model);
        });

        verify(simulationArtifactService).downloadArtifact("job-123", USER_ID, Output.MODEL);
    }

    @Test
    void downloadWaveform_shouldReturn404WhenArtifactUnavailable() {
        when(simulationArtifactService.downloadArtifact("job-123", USER_ID, Output.WAVEFORM)).thenReturn(Optional.empty());

        var result = assertThat(mockMvcTester.get().uri("/api/v1/simulate/download-waveform/job-123").with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        result.hasStatus(HttpStatus.NOT_FOUND);

        verify(simulationArtifactService).downloadArtifact("job-123", USER_ID, Output.WAVEFORM);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfiguration {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).csrf(AbstractHttpConfigurer::disable);

            return http.build();
        }
    }
}
