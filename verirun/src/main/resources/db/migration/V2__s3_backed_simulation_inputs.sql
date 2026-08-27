DELETE
FROM simulation_jobs;

ALTER TABLE simulation_jobs
DROP
COLUMN directory_path;

ALTER TABLE simulation_jobs
    ADD COLUMN testbench_expected BOOLEAN NOT NULL;
