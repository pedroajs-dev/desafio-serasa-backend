package com.serasa.balancas.scalereading;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.branch.BranchRepository;
import com.serasa.balancas.scale.Scale;
import com.serasa.balancas.scale.ScaleRepository;
import com.serasa.balancas.stabilization.WeighingPersistencePort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ScaleReadingPersistenceFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScaleRepository scaleRepository;

    @Autowired
    private BranchRepository branchRepository;

    @MockBean
    private WeighingPersistencePort weighingPersistencePort;

    private Scale createScale(String apiKey) {
        Branch branch = branchRepository.findById(1L).orElseThrow();
        String scaleId = "TEST-BAL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return scaleRepository.save(new Scale(scaleId, branch, apiKey));
    }

    private String readingBody(String scaleId, String plate, double weight, Long seq) throws Exception {
        return objectMapper.writeValueAsString(new ScaleReadingRequest(scaleId, plate, weight, seq, null));
    }

    @Test
    void failedPersistEvictsSeqSoRetryIsReprocessed() throws Exception {
        // First persist() throws (downstream failure), second succeeds.
        doThrow(new RuntimeException("persistence boom")).doNothing().when(weighingPersistencePort).persist(any());

        Scale scale = createScale("valid-key-" + UUID.randomUUID().toString().substring(0, 6));
        String plate = "PLT-RETRY";

        // Readings 1-16 (no seq) fill the stabilization window without triggering persistence.
        for (int i = 0; i < 16; i++) {
            mockMvc.perform(post("/api/scales/readings")
                            .header("X-Scale-Key", scale.getApiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(readingBody(scale.getId(), plate, 12500.0, null)))
                    .andExpect(status().isAccepted());
        }

        // Reading 17 (seq=100) stabilizes and triggers persist(), which throws. The controller's
        // catch block evicts seq=100 so a retry is not treated as a duplicate. The rethrown
        // exception may surface as a 5xx response or propagate out of MockMvc; both are acceptable.
        try {
            mockMvc.perform(post("/api/scales/readings")
                    .header("X-Scale-Key", scale.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(readingBody(scale.getId(), plate, 12500.0, 100L)));
        } catch (Exception expectedPersistenceFailure) {
            // expected: persist() threw and was rethrown
        }

        // Retry of the failed reading with the SAME seq=100. Because the key was evicted, it is
        // NOT discarded — it reaches stabilization again and persist() is invoked a second time.
        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", scale.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(scale.getId(), plate, 12500.0, 100L)))
                .andExpect(status().isAccepted());

        // persist() called twice: the failed attempt and the successful retry. Had the seq not
        // been evicted, the retry would have been silently discarded and persist() called only once.
        verify(weighingPersistencePort, times(2)).persist(any());
    }
}
