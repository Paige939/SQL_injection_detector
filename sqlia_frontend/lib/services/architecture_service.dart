import 'package:dio/dio.dart';

import '../core/api_client.dart';
import '../models/architecture_performance.dart';
import '../models/architecture_prediction.dart';
import '../models/incremental_algorithm_performance.dart';

class ArchitectureService {
  final ApiClient client;

  ArchitectureService(this.client);

  Future<List<ArchitecturePrediction>> predictAll(
    String sql, {
    bool extendedOnly = false,
  }) async {
    final response = await client.dio.post(
      extendedOnly
          ? '/api/extended/architectures/predict'
          : '/api/architectures/predict',
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
    bool extendedOnly = false,
  }) async {
    final response = await client.dio.post(
      extendedOnly
          ? '/api/extended/performance/compare'
          : '/api/performance/compare',
      options: Options(receiveTimeout: const Duration(minutes: 10)),
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

  Future<List<IncrementalAlgorithmPerformance>> compareIncrementalAlgorithms({
    required String datasetPath,
    required int sampleSize,
    bool extendedOnly = false,
  }) async {
    final response = await client.dio.post(
      extendedOnly
          ? '/api/extended/incremental/algorithms/compare'
          : '/api/incremental/algorithms/compare',
      data: {'datasetPath': datasetPath, 'sampleSize': sampleSize},
    );
    return (response.data as List)
        .map(
          (value) => IncrementalAlgorithmPerformance.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .toList();
  }
}
