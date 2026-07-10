package com.serasa.balancas.scale;

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
class ScaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsAndPersistsScale() throws Exception {
        ScaleRequest request = new ScaleRequest("BAL-100", 1L, "key-100");

        mockMvc.perform(post("/api/scales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("BAL-100"))
                .andExpect(jsonPath("$.branchId").value(1))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void findAllReturnsScalesWithBranchId() throws Exception {
        ScaleRequest request = new ScaleRequest("BAL-101", 1L, "key-101");
        mockMvc.perform(post("/api/scales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/scales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'BAL-101')].branchId").value(1));
    }

    @Test
    void rejectsBlankApiKey() throws Exception {
        ScaleRequest request = new ScaleRequest("BAL-102", 1L, "");

        mockMvc.perform(post("/api/scales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNullBranchId() throws Exception {
        ScaleRequest request = new ScaleRequest("BAL-103", null, "key-103");

        mockMvc.perform(post("/api/scales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404WhenBranchDoesNotExist() throws Exception {
        ScaleRequest request = new ScaleRequest("BAL-104", 999999L, "key-104");

        mockMvc.perform(post("/api/scales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
