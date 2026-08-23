package app.verirun.controller;

import app.verirun.service.JobQueueService;
import app.verirun.entity.Role;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.SimulationArtifactService;
import app.verirun.service.SimulationQueryService;
import app.verirun.service.SimulationSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.SecurityFilterChain;
import app.verirun.security.TokenAuthenticationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebMvcTest(value = SimulationController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TokenAuthenticationFilter.class))
@Import(SimulationControllerTest.TestSecurityConfiguration.class)
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private MockMvcTester mockMvcTester;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private UserDetailsImpl authenticatedUser;

    @MockitoBean
    private SimulationSubmissionService simulationSubmissionService;

    @MockitoBean
    private SimulationQueryService simulationQueryService;

    @MockitoBean
    private JobQueueService jobQueueService;

    @MockitoBean
    private SimulationArtifactService simulationArtifactService;

    @BeforeEach
    void setUp() {
        mockMvcTester = MockMvcTester.create(mockMvc);

        Role role = new Role();
        role.setName("REGISTERED_USER");
        authenticatedUser = new UserDetailsImpl(USER_ID, "user@verirun.com", "password", role);
    }

    @Test
    void runSimulation_shouldReturn200AndJobId_whenValidRequest() throws IOException {
        String validJson = """
            {
              "designCode": "module ALU(); endmodule",
              "testbenchCode": null
            }
            """;
        when(simulationSubmissionService.createSimulationJob(any(), eq(USER_ID))).thenReturn("job-123");

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.post()
                .uri("/api/v1/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson)
                .with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        resultAssert.hasStatus(HttpStatus.OK);
        resultAssert.bodyJson().extractingPath("$.jobId").isEqualTo("job-123");
        resultAssert.bodyJson().extractingPath("$.status").isEqualTo("PENDING");

        verify(simulationSubmissionService, times(1)).createSimulationJob(any(), eq(USER_ID));
        verify(jobQueueService, times(1)).enqueueJob("job-123");
    }

    @Test
    void runSimulation_shouldReturn400BadRequest_whenCodeIsTooLarge() {
        String largeCode = "a".repeat(100_001);
        String invalidJson = String.format("""
            {
              "designCode": "%s",
              "testbenchCode": null
            }
            """, largeCode);

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.post()
                .uri("/api/v1/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
                .with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        resultAssert.hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getJobStatus_shouldReturn404_whenJobNotFound() {
        when(simulationQueryService.getJobStatus("missing-job", USER_ID)).thenReturn(Optional.empty());

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.get()
                .uri("/api/v1/jobs/missing-job/status")
                .with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        resultAssert.hasStatus(HttpStatus.NOT_FOUND);
        verify(simulationQueryService).getJobStatus("missing-job", USER_ID);
    }

    @Test
    void downloadModel_shouldPreserveResponseHeadersForOwnedArtifact() {
        Resource resource = mock(Resource.class);
        when(simulationArtifactService.downloadArtifact("job-123", USER_ID, "model.zip"))
                .thenReturn(Optional.of(resource));

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.get()
                .uri("/api/v1/simulate/download/job-123")
                .with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        resultAssert.hasStatus(HttpStatus.OK);
        resultAssert.satisfies(result -> {
            assertThat(result.getResponse().getHeader("Content-Disposition"))
                    .isEqualTo("attachment; filename=\"verilator_model_job-123.zip\"");
            assertThat(result.getResponse().getHeader("Cache-Control"))
                    .isEqualTo("private, max-age=3600");
            assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        });
        verify(simulationArtifactService).downloadArtifact("job-123", USER_ID, "model.zip");
    }

    @Test
    void downloadWaveform_shouldReturn404ForUnownedArtifact() {
        when(simulationArtifactService.downloadArtifact("job-123", USER_ID, "waveform.vcd"))
                .thenReturn(Optional.empty());

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.get()
                .uri("/api/v1/simulate/download-waveform/job-123")
                .with(SecurityMockMvcRequestPostProcessors.user(authenticatedUser)));

        resultAssert.hasStatus(HttpStatus.NOT_FOUND);
        verify(simulationArtifactService).downloadArtifact("job-123", USER_ID, "waveform.vcd");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfiguration {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

}
