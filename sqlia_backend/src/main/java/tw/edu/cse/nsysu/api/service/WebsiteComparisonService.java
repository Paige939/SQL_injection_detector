package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import tw.edu.cse.nsysu.api.dto.SimulationRequest;

/** Interactive website benchmark; independent of the frozen research protocol. */
public final class WebsiteComparisonService {
    private final SimulationService simulation;
    private final ModelStateService state;

    public WebsiteComparisonService(SimulationService simulation, ModelStateService state) {
        this.simulation = simulation;
        this.state = state;
    }

    public List<Map<String, Object>> compare(SimulationRequest request, List<String> architectures)
            throws Exception {
        if (request.datasetPath == null || request.sampleSize <= 0 || request.updateModel
                || request.checkpointDirectory != null || architectures.isEmpty()
                || new HashSet<>(architectures).size() != architectures.size()
                || !FrozenArchitectureComparisonService.ARCHITECTURES.containsAll(architectures)) {
            throw new IllegalArgumentException(
                    "Website comparison requires a dataset, positive sample size and updates disabled; "
                    + "saved checkpoints belong to the separate research runner.");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        synchronized (state) {
            for (String architecture : architectures) {
                SimulationRequest selected = new SimulationRequest();
                selected.datasetPath = request.datasetPath;
                selected.sampleSize = request.sampleSize;
                selected.architecture = architecture;
                var metrics = simulation.run(selected);
                Map<String, Object> result = mapper.convertValue(metrics, new TypeReference<>() {});
                result.put("architecture", architecture);
                result.put("threshold", state.getArchitectureThresholds().get(architecture));
                result.put("trainingSamples", state.getTotalTrained());
                result.put("evaluationMode", "interactive_website_snapshot");
                results.add(result);
            }
        }
        return results;
    }
}
