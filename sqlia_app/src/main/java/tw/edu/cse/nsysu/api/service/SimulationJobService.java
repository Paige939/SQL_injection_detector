package tw.edu.cse.nsysu.api.service;

import tw.edu.cse.nsysu.api.dto.SimulationJob;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
// A service class that manages simulation jobs, allowing clients to start new simulations, retrieve the status of existing jobs, and shut down the service. It uses an internal executor to run simulations asynchronously and a concurrent map to store active jobs.
public class SimulationJobService {
    private final SimulationService simulationService;
    // An executor service that runs simulation jobs asynchronously, allowing multiple jobs to be processed concurrently without blocking the main application thread.
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    // A thread-safe map that stores active simulation jobs, allowing concurrent access and modification from multiple threads.
    private final Map<String, SimulationJob> jobs =
        new ConcurrentHashMap<>();
    // Constructs a new SimulationJobService with the provided [SimulationService], initializing an internal executor for running simulation jobs asynchronously and a concurrent map to store active jobs.
    public SimulationJobService(SimulationService simulationService) {
        this.simulationService = simulationService;
    }
    // Starts a new simulation job based on the provided [SimulationRequest], generating a unique job ID, storing the job in an internal map, and executing the simulation asynchronously. Returns the created SimulationJob object.
    public SimulationJob start(SimulationRequest request) {
        String jobId = UUID.randomUUID().toString();
        SimulationJob job = new SimulationJob(jobId);
        jobs.put(jobId, job);

        executor.submit(() -> {
            try {
                job.status = "RUNNING";

                job.result = simulationService.run(
                    request,
                    progress -> {
                        job.processed = progress.totalProcessed;
                        job.total = progress.totalSamples;
                    }
                );

                job.processed = job.result.total;
                job.total = job.result.total;
                job.status = "COMPLETED";
            } catch (Exception error) {
                job.status = "FAILED";
                job.error = error.getMessage();
            }
        });

        return job;
    }
    // Retrieves the simulation job with the specified [jobId] from the internal job map, returning null if no such job exists.
    public SimulationJob get(String jobId) {
        return jobs.get(jobId);
    }
    // Shuts down the executor service, stopping any further job submissions and allowing currently running jobs to complete.
    public void shutdown() {
        executor.shutdown();
    }
}