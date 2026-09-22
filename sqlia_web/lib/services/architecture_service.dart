import '../core/api_client.dart';
import '../models/architecture_performance.dart';
import '../models/architecture_prediction.dart';

class ArchitectureService {
  final ApiClient client;

  ArchitectureService(this.client);

  Future<List<ArchitecturePrediction>> predictAll(String sql) async {
    final response = await client.dio.post(
      '/api/architectures/predict',
      data: {'sql': sql},
    );
    return (response.data as List)
        .map(
          (value) => ArchitecturePrediction.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .toList();
  }

  Future<List<ArchitecturePerformance>> compare({
    required String datasetPath,
    required int sampleSize,
  }) async {
    final response = await client.dio.post(
      '/api/performance/compare',
      data: {'datasetPath': datasetPath, 'sampleSize': sampleSize},
    );

    return (response.data as List)
        .map(
          (value) => ArchitecturePerformance.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .toList();
  }
}
