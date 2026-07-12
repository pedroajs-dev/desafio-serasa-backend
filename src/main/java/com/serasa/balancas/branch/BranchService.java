package com.serasa.balancas.branch;

import com.serasa.balancas.common.exception.DuplicateResourceException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BranchService {

    private final BranchRepository branchRepository;

    public BranchService(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

    public Branch create(Branch branch) {
        if (branchRepository.existsByName(branch.getName())) {
            throw new DuplicateResourceException(
                    "Branch with name " + branch.getName() + " already exists");
        }
        branch.setId(null);
        return branchRepository.save(branch);
    }

    public List<Branch> findAll() {
        return branchRepository.findAll();
    }
}
