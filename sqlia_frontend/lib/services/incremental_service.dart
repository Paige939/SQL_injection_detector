import '../core/api_client.dart';
import '../models/accuracy_point.dart';
import '../models/incremental_feed_result.dart';

// A service class that handles incremental learning operations using the [ApiClient].
class IncrementalService {
  final ApiClient client;
  IncrementalService(this.client);
  // Fetches the status of the incremental learning process from the API and returns it as a map of key-value pairs.
  Future<Map<String, dynamic>> getStatus() async {
    final res = await client.dio.get('/api/incremental/status');
    // Casts the response data to a map of string keys and dynamic values and returns it.
    return Map<String, dynamic>.from(res.data as Map);
  }

  Future<List<Map<String, dynamic>>> getModels() async {
    final response = await client.dio.get('/api/incremental/models');
    return (response.data as List)
        .map((value) => Map<String, dynamic>.from(value as Map))
        .toList();
  }

  // Fetches the accuracy history from the API and returns it as a list of [AccuracyPoint] instances.
  Future<List<AccuracyPoint>> getAccuracyHistory({String? modelId}) async {
    final res = await client.dio.get(
      '/api/stats/accuracy',
      queryParameters: {'modelId': ?modelId},
    );
    // Casts the response data to a list of dynamic values and maps each value to an [AccuracyPoint] instance by converting the value to a JSON map and using the [AccuracyPoint.fromJson] factory constructor. Finally, it converts the mapped values to a list and returns it.
    return (res.data as List)
        .map(
          (value) =>
              AccuracyPoint.fromJson(Map<String, dynamic>.from(value as Map)),
        )
        .toList();
  }

  Future<IncrementalFeedResult> feed({
    required String sql,
    int? label,
    String modelId = 'CURRENT_ONLINE_BAGGING_NB',
  }) async {
    final response = await client.dio.post(
      '/api/incremental/feed',
      data: {'sql': sql, 'label': ?label, 'modelId': modelId},
    );

    return IncrementalFeedResult.fromJson(
      Map<String, dynamic>.from(response.data as Map),
    );
  }
}
