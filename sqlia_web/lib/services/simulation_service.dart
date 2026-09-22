import '../core/api_client.dart';
import '../models/simulation_job.dart';
// A service class that handles simulation operations using the [ApiClient].
class SimulationService {
  final ApiClient client;

  SimulationService(this.client);
  // Starts a new simulation job by sending the provided dataset path, sample size, and model update option to the API. Returns a [SimulationJob] instance representing the started job.
  Future<SimulationJob> start({
    required String datasetPath,
    required int sampleSize,
    required bool updateModel,
  }) async {
    final response = await client.dio.post(
      '/api/simulation/jobs',
      data: {
        'datasetPath': datasetPath,
        'sampleSize': sampleSize,
        'updateModel': updateModel,
      },
    );
    // Casts the response data to a map of string keys and dynamic values and creates a [SimulationJob] instance from it using the [SimulationJob.fromJson] factory constructor. Finally, it returns the created [SimulationJob] instance.
    return SimulationJob.fromJson(
      Map<String, dynamic>.from(response.data as Map),
    );
  }
  // Fetches the status of a simulation job by its ID from the API and returns it as a [SimulationJob] instance.
  Future<SimulationJob> getJob(String jobId) async {
    final response = await client.dio.get(
      '/api/simulation/jobs/$jobId',
    );
    // Casts the response data to a map of string keys and dynamic values and creates a [SimulationJob] instance from it using the [SimulationJob.fromJson] factory constructor. Finally, it returns the created [SimulationJob] instance.
    return SimulationJob.fromJson(
      Map<String, dynamic>.from(response.data as Map),
    );
  }
}