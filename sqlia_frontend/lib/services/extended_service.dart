import '../core/api_client.dart';

class ExtendedService {
  final ApiClient client;

  ExtendedService(this.client);

  Future<List<Map<String, dynamic>>> rules() async {
    final response = await client.dio.get('/api/extended/rules');
    return (response.data as List)
        .map((v) => Map<String, dynamic>.from(v as Map))
        .toList();
  }

  Future<List<Map<String, dynamic>>> statistics() async {
    final response = await client.dio.get('/api/extended/statistics');
    return (response.data as List)
        .map((v) => Map<String, dynamic>.from(v as Map))
        .toList();
  }

  Future<List<Map<String, dynamic>>> ablation() async {
    final response = await client.dio.get('/api/extended/ablation');
    return (response.data as List)
        .map((v) => Map<String, dynamic>.from(v as Map))
        .toList();
  }
}
