import '../core/api_client.dart';
import '../models/detect_result.dart';

// A service class that handles detection operations using the [ApiClient].
class DetectionService{
  // The [ApiClient] instance used for making HTTP requests.
  final ApiClient client;
  // Creates an instance of [DetectionService] with the specified [client].
  DetectionService(this.client);
  // Performs a detection operation by sending the provided [sql] to the API and returning the result as a [DetectResult].
  Future<DetectResult> detect(String sql) async{
    final res = await client.dio.post('/api/detect', data: {'sql': sql});
    return DetectResult.fromJson(res.data as Map<String, dynamic>);
  }
}