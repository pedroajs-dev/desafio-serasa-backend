package com.serasa.balancas.scale;

import com.serasa.balancas.branch.Branch;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
public class Scale {

    @Id
    private String id;

    @NotNull
    @ManyToOne
    private Branch branch;

    // Stored in plaintext for this delivery — accepted trade-off, see README.
    @NotBlank
    private String apiKey;

    public Scale() {
    }

    public Scale(String id, Branch branch, String apiKey) {
        this.id = id;
        this.branch = branch;
        this.apiKey = apiKey;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Branch getBranch() {
        return branch;
    }

    public void setBranch(Branch branch) {
        this.branch = branch;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
