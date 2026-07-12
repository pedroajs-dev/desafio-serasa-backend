package com.serasa.balancas.branch;

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
@RequestMapping("/api/branches")
public class BranchController {

    private final BranchService branchService;

    public BranchController(BranchService branchService) {
        this.branchService = branchService;
    }

    @PostMapping
    public ResponseEntity<Branch> create(@Valid @RequestBody Branch branch) {
        Branch saved = branchService.create(branch);
        return ResponseEntity.created(URI.create("/api/branches/" + saved.getId())).body(saved);
    }

    @GetMapping
    public List<Branch> findAll() {
        return branchService.findAll();
    }
}
