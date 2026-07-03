package app.verirun.controller;

import app.verirun.service.JobQueueService;
import app.verirun.service.SimulationQueryService;
import app.verirun.service.SimulationSubmissionService;
import app.verirun.storage.ArtifactStorageService;
import app.verirun.util.TokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private MockMvcTester mockMvcTester;

    @MockitoBean
    private SimulationSubmissionService simulationSubmissionService;

    @MockitoBean
    private SimulationQueryService simulationQueryService;

    @MockitoBean
    private JobQueueService jobQueueService;
    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private TokenUtil tokenUtil;

    @MockitoBean
    private ArtifactStorageService storageService;

    @BeforeEach
    void setUp() {
        mockMvcTester = MockMvcTester.create(mockMvc);
    }

    @Test
    @WithMockUser(roles = "REGISTERED_USER")
    void runSimulation_shouldReturn200AndJobId_WhenValidRequest() throws IOException {
        String validJson = """
            {
              "designCode": "module ALU(); endmodule",
              "testbenchCode": null
            }
            """;
        when(simulationSubmissionService.createSimulationJob(any())).thenReturn("job-123");

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.post()
                .uri("/api/v1/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson));

        resultAssert.hasStatus(HttpStatus.OK);
        resultAssert.bodyJson().extractingPath("$.jobId").isEqualTo("job-123");
        resultAssert.bodyJson().extractingPath("$.status").isEqualTo("PENDING");

        verify(simulationSubmissionService, times(1)).createSimulationJob(any());
        verify(jobQueueService, times(1)).enqueueJob("job-123");
    }

    @Test
    @WithMockUser(roles = "REGISTERED_USER")
    void runSimulation_shouldReturn400BadRequest_WhenCodeIsTooLarge() {
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
                .content(invalidJson));

        resultAssert.hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(roles = "REGISTERED_USER")
    void getJobStatus_shouldReturn404_WhenJobNotFound() {
        when(simulationQueryService.getJobStatus("missing-job")).thenReturn(Optional.empty());

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.get()
                .uri("/api/v1/jobs/missing-job/status"));

        resultAssert.hasStatus(HttpStatus.NOT_FOUND);
    }
}