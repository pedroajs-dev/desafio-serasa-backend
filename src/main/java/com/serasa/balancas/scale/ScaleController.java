package com.serasa.balancas.scale;

import com.serasa.balancas.branch.Branch;
import com.serasa.balancas.branch.BranchRepository;
import com.serasa.balancas.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scales")
public class ScaleController {

    private final ScaleRepository scaleRepository;
    private final BranchRepository branchRepository;

    public ScaleController(ScaleRepository scaleRepository, BranchRepository branchRepository) {
        this.scaleRepository = scaleRepository;
        this.branchRepository = branchRepository;
    }

    @PostMapping
    public ResponseEntity<ScaleResponse> create(@Valid @RequestBody ScaleRequest request) {
        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found with id " + request.branchId()));
        Scale scale = new Scale(request.id(), branch, request.apiKey());
        Scale saved = scaleRepository.save(scale);
        return ResponseEntity.created(URI.create("/api/scales/" + saved.getId())).body(ScaleResponse.from(saved));
    }

    @GetMapping
    public List<ScaleResponse> findAll() {
        return scaleRepository.findAll().stream().map(ScaleResponse::from).toList();
    }
}
