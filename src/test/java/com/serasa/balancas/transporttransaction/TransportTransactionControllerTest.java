package com.serasa.balancas.transporttransaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serasa.balancas.truck.Truck;
import com.serasa.balancas.truck.TruckRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransportTransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TruckRepository truckRepository;

    /**
     * Persists a fresh truck with a unique plate, so each test gets its own truck and
     * never collides with the "one open transaction per truck" guard in the controller.
     */
    private long createTruck() {
        String plate = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return truckRepository.save(new Truck(plate, 5000.0)).getId();
    }

    private long createTransaction() throws Exception {
        return createTransaction(createTruck());
    }

    private long createTransaction(long truckId) throws Exception {
        TransportTransactionRequest request = new TransportTransactionRequest(truckId, 1L, 1L);
        String response = mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void createsTransactionInTransitStatus() throws Exception {
        TransportTransactionRequest request = new TransportTransactionRequest(createTruck(), 1L, 1L);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.truckId").value(request.truckId()))
                .andExpect(jsonPath("$.grainTypeId").value(1))
                .andExpect(jsonPath("$.branchId").value(1))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.startDate").exists())
                .andExpect(jsonPath("$.endDate").doesNotExist());
    }

    @Test
    void returns404WhenTruckDoesNotExist() throws Exception {
        TransportTransactionRequest request = new TransportTransactionRequest(999999L, 1L, 1L);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns400WhenTruckAlreadyHasOpenTransaction() throws Exception {
        long truckId = createTruck();
        createTransaction(truckId);

        TransportTransactionRequest secondRequest = new TransportTransactionRequest(truckId, 1L, 1L);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allowsNewTransactionAfterPreviousOneCompleted() throws Exception {
        long truckId = createTruck();
        long firstId = createTransaction(truckId);

        mockMvc.perform(patch("/api/transactions/" + firstId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());

        TransportTransactionRequest secondRequest = new TransportTransactionRequest(truckId, 1L, 1L);

        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isCreated());
    }

    @Test
    void getByIdReturnsFullTransaction() throws Exception {
        long id = createTransaction();

        mockMvc.perform(get("/api/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));
    }

    @Test
    void getByIdReturns404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/transactions/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByIdReturns400WhenIdIsNotNumeric() throws Exception {
        mockMvc.perform(get("/api/transactions/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchStatusUpdatesTransaction() throws Exception {
        long id = createTransaction();

        mockMvc.perform(patch("/api/transactions/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"AT_DOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_DOCK"));
    }

    @Test
    void patchStatusReturns404WhenTransactionNotFound() throws Exception {
        mockMvc.perform(patch("/api/transactions/999999/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"AT_DOCK\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchStatusReturns400WhenStatusInvalid() throws Exception {
        long id = createTransaction();

        mockMvc.perform(patch("/api/transactions/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_A_REAL_STATUS\"}"))
                .andExpect(status().isBadRequest());
    }
}
