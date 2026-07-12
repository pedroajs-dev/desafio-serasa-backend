package com.serasa.balancas.truck;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TruckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TruckRepository truckRepository;

    @Test
    void createsAndPersistsTruck() throws Exception {
        Truck truck = new Truck("QWE1R23", 8000.0);

        mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(truck)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.licensePlate").value("QWE1R23"))
                .andExpect(jsonPath("$.tare").value(8000.0));
    }

    @Test
    void rejectsBlankLicensePlate() throws Exception {
        Truck truck = new Truck("", 8000.0);

        mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(truck)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonPositiveTare() throws Exception {
        Truck truck = new Truck("ZXC4V56", -1.0);

        mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(truck)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByIdReturnsTruck() throws Exception {
        Truck truck = new Truck("POI7U89", 7500.0);
        String response = mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(truck)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Truck saved = objectMapper.readValue(response, Truck.class);

        mockMvc.perform(get("/api/trucks/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licensePlate").value("POI7U89"))
                .andExpect(jsonPath("$.tare").value(7500.0));
    }

    @Test
    void getByIdReturns404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/trucks/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateLicensePlateWithConflict() throws Exception {
        Truck truck = new Truck("DUP1L23", 8500.0);
        String payload = objectMapper.writeValueAsString(truck);

        mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("Truck with license plate DUP1L23 already exists"));

        long count = truckRepository.findAll().stream()
                .filter(t -> "DUP1L23".equals(t.getLicensePlate()))
                .count();
        assertEquals(1, count);
    }

    @Test
    void ignoresClientSuppliedIdOnCreate() throws Exception {
        Truck truck = new Truck("LKJ2H34", 6000.0);
        truck.setId(1L);

        mockMvc.perform(post("/api/trucks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(truck)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(1)))
                .andExpect(jsonPath("$.licensePlate").value("LKJ2H34"));
    }
}
