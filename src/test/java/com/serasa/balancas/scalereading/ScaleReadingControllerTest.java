package com.serasa.balancas.scalereading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.branch.BranchRepository;
import com.serasa.balancas.graintype.GrainType;
import com.serasa.balancas.graintype.GrainTypeRepository;
import com.serasa.balancas.scale.Scale;
import com.serasa.balancas.scale.ScaleRepository;
import com.serasa.balancas.transporttransaction.TransactionStatus;
import com.serasa.balancas.transporttransaction.TransportTransaction;
import com.serasa.balancas.transporttransaction.TransportTransactionRepository;
import com.serasa.balancas.truck.Truck;
import com.serasa.balancas.truck.TruckRepository;
import com.serasa.balancas.weighingrecord.WeighingRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ScaleReadingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScaleRepository scaleRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private TruckRepository truckRepository;

    @Autowired
    private GrainTypeRepository grainTypeRepository;

    @Autowired
    private TransportTransactionRepository transportTransactionRepository;

    @Autowired
    private WeighingRecordRepository weighingRecordRepository;

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private Scale createScale(String apiKey) {
        Branch branch = branchRepository.findById(1L).orElseThrow();
        String scaleId = "TEST-BAL-" + uniqueSuffix();
        return scaleRepository.save(new Scale(scaleId, branch, apiKey));
    }

    private TransportTransaction openTransaction(String plate, double tare) {
        Truck truck = truckRepository.save(new Truck(plate, tare));
        GrainType grainType = grainTypeRepository.findById(1L).orElseThrow();
        Branch branch = branchRepository.findById(1L).orElseThrow();
        return transportTransactionRepository.save(new TransportTransaction(truck, grainType, branch));
    }

    private String readingBody(String scaleId, String plate, double weight) throws Exception {
        return objectMapper.writeValueAsString(new ScaleReadingRequest(scaleId, plate, weight));
    }

    @Test
    void returns202WhenAuthenticatedWithValidPayload() throws Exception {
        Scale scale = createScale("valid-key-" + uniqueSuffix());

        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", scale.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(scale.getId(), "PLT0001", 1000.0)))
                .andExpect(status().isAccepted());
    }

    @Test
    void returns401WhenHeaderMissing() throws Exception {
        Scale scale = createScale("valid-key-" + uniqueSuffix());

        mockMvc.perform(post("/api/scales/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(scale.getId(), "PLT0002", 1000.0)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenKeyDoesNotMatchScale() throws Exception {
        Scale scale = createScale("valid-key-" + uniqueSuffix());

        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(scale.getId(), "PLT0003", 1000.0)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenKeyBelongsToDifferentScale() throws Exception {
        Scale scaleA = createScale("key-a-" + uniqueSuffix());
        Scale scaleB = createScale("key-b-" + uniqueSuffix());

        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", scaleB.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody(scaleA.getId(), "PLT0004", 1000.0)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WhenScaleIdUnknown() throws Exception {
        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", "any-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingBody("NON-EXISTENT-SCALE", "PLT0005", 1000.0)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns400WhenPlateMissing() throws Exception {
        Scale scale = createScale("valid-key-" + uniqueSuffix());
        String body = "{\"id\":\"" + scale.getId() + "\",\"weight\":1000.0}";

        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", scale.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenWeightMissing() throws Exception {
        Scale scale = createScale("valid-key-" + uniqueSuffix());
        String body = "{\"id\":\"" + scale.getId() + "\",\"plate\":\"PLT0006\"}";

        mockMvc.perform(post("/api/scales/readings")
                        .header("X-Scale-Key", scale.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stableReadingsPersistWeighingRecordAndCompleteTransaction() throws Exception {
        Scale scale = createScale("valid-key-" + uniqueSuffix());
        String plate = "PLT" + uniqueSuffix();
        TransportTransaction transaction = openTransaction(plate, 8500.0);
        long countBefore = weighingRecordRepository.count();

        // stabilization: window-size=15, consecutive-windows=3, std-dev-threshold=5.0 (application.yml)
        // 17 identical readings fill the window once and then hold it stable for 2 more windows.
        for (int i = 0; i < 17; i++) {
            mockMvc.perform(post("/api/scales/readings")
                            .header("X-Scale-Key", scale.getApiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(readingBody(scale.getId(), plate, 12500.0)))
                    .andExpect(status().isAccepted());
        }

        assertThat(weighingRecordRepository.count()).isEqualTo(countBefore + 1);

        TransportTransaction reloaded = transportTransactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(reloaded.getEndDate()).isNotNull();
        assertThat(reloaded.getNetWeightKg()).isEqualTo(4000.0);
    }
}
