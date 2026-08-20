package app.verirun.repository;

import app.verirun.entity.SimulationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SimulationJobRepository extends JpaRepository<SimulationJob, UUID> {

    Optional<SimulationJob> findByJobId(String jobId);

    Optional<SimulationJob> findByJobIdAndOwner_Id(String jobId, UUID ownerId);

    boolean existsByJobIdAndOwner_Id(String jobId, UUID ownerId);

    @Modifying
    @Transactional
    @Query("UPDATE SimulationJob j SET j.status = 'RUNNING', j.startedAt = :now " +
            "WHERE j.jobId = :jobId AND j.status = 'PENDING'")
    int claimJob(@Param("jobId") String jobId, @Param("now") Instant now);

    @Query("""
        SELECT j FROM SimulationJob j
        WHERE j.status = 'RUNNING'
          AND j.startedAt IS NOT NULL
          AND j.startedAt <= :cutoff
    """)
    List<SimulationJob> findStuckJobs(@Param("cutoff") Instant cutoff);
}
