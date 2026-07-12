package com.serasa.balancas.graintype;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GrainTypeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GrainTypeRepository grainTypeRepository;

    @Test
    void createsAndPersistsGrainType() throws Exception {
        GrainType grainType = new GrainType("Feijao", new BigDecimal("220.00"), 15000.0, 5000.0);

        mockMvc.perform(post("/api/grain-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grainType)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Feijao"))
                .andExpect(jsonPath("$.purchasePricePerTon").value(220.00))
                .andExpect(jsonPath("$.maxReferenceStock").value(15000.0))
                .andExpect(jsonPath("$.currentStock").value(5000.0));
    }

    @Test
    void rejectsMissingPrice() throws Exception {
        GrainType grainType = new GrainType("Feijao", null, 15000.0, 5000.0);

        mockMvc.perform(post("/api/grain-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grainType)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsAllGrainTypesIncludingSeedData() throws Exception {
        mockMvc.perform(get("/api/grain-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(3)));
    }

    @Test
    void rejectsDuplicateNameWithConflict() throws Exception {
        GrainType grainType = new GrainType("Cevada", new BigDecimal("180.00"), 12000.0, 3000.0);
        String payload = objectMapper.writeValueAsString(grainType);

        mockMvc.perform(post("/api/grain-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/grain-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("GrainType with name Cevada already exists"));

        long count = grainTypeRepository.findAll().stream()
                .filter(g -> "Cevada".equals(g.getName()))
                .count();
        assertEquals(1, count);
    }

    @Test
    void ignoresClientSuppliedIdOnCreate() throws Exception {
        GrainType grainType = new GrainType("Trigo", new BigDecimal("150.00"), 10000.0, 2000.0);
        grainType.setId(1L);

        mockMvc.perform(post("/api/grain-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grainType)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(1)))
                .andExpect(jsonPath("$.name").value("Trigo"));
    }
}
