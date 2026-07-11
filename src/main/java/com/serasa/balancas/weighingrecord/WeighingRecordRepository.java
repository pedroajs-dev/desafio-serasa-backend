package com.serasa.balancas.weighingrecord;

import com.serasa.balancas.report.ScaleRankingResponse;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WeighingRecordRepository extends JpaRepository<WeighingRecord, Long> {

    @Query("SELECT new com.serasa.balancas.report.ScaleRankingResponse(w.scale.id, COUNT(w)) "
            + "FROM WeighingRecord w "
            + "GROUP BY w.scale.id "
            + "ORDER BY COUNT(w) DESC")
    List<ScaleRankingResponse> countByScaleOrderedDesc();
}
