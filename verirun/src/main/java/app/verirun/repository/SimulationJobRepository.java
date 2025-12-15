package app.verirun.repository;

import app.verirun.entity.SimulationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SimulationJobRepository extends JpaRepository<SimulationJob, UUID> {

    Optional<SimulationJob> findByJobId(String jobId);

    List<SimulationJob> findByCleanedUpFalseAndCleanupScheduledAtBefore(Instant cutoff);

    @Transactional
    @Modifying
    @Query("UPDATE SimulationJob j SET j.cleanedUp = true WHERE j.jobId = :jobId")
    void markAsCleanedUp(String jobId);
}