import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/defense_result.dart';
import '../providers/detection_provider.dart';
import '../core/api_client.dart';

final defenseServiceProvider = Provider(
  (ref) => DefenseService(ref.watch(apiClientProvider)),
);

class DefenseService {
  final ApiClient client;
  DefenseService(this.client);

  Future<Map<String, dynamic>> status() async => Map<String, dynamic>.from(
    (await client.dio.get('/api/defense/status')).data as Map,
  );

  Future<DefenseResult> search({
    required String keyword,
    required String architecture,
    required String algorithm,
  }) async {
    try {
      final response = await client.dio.post(
        '/api/defense/search',
        data: {
          'keyword': keyword,
          'architecture': architecture,
          'algorithm': algorithm,
        },
        options: Options(
          validateStatus: (status) => status == 200 || status == 403,
        ),
      );
      return DefenseResult.fromJson(
        Map<String, dynamic>.from(response.data as Map),
      );
    } on DioException catch (error) {
      final data = error.response?.data;
      if (data is Map &&
          data['requestId'] is String &&
          data['errorCode'] is String) {
        return DefenseResult.fromJson(Map<String, dynamic>.from(data));
      }
      rethrow;
    }
  }

  Future<DefenseResult> resolveReview({
    required String requestId,
    required String decision,
  }) async {
    try {
      final response = await client.dio.post(
        '/api/defense/review',
        data: {'requestId': requestId, 'decision': decision},
        options: Options(
          validateStatus: (status) => status == 200 || status == 403,
        ),
      );
      return DefenseResult.fromJson(
        Map<String, dynamic>.from(response.data as Map),
      );
    } on DioException catch (error) {
      final data = error.response?.data;
      if (data is Map &&
          data['requestId'] is String &&
          data['errorCode'] is String) {
        return DefenseResult.fromJson(Map<String, dynamic>.from(data));
      }
      rethrow;
    }
  }

  Future<Map<String, dynamic>> audit() async => Map<String, dynamic>.from(
    (await client.dio.get('/api/defense/audit')).data as Map,
  );

  Future<Map<String, dynamic>> latestEvaluation() async =>
      Map<String, dynamic>.from(
        (await client.dio.get('/api/defense/evaluation')).data as Map,
      );

  Future<Map<String, dynamic>> evaluate({
    required bool extendedOnly,
    required int sampleSize,
  }) async {
    final response = await client.dio.post(
      '/api/defense/evaluation',
      data: {'extendedOnly': extendedOnly, 'sampleSize': sampleSize},
      options: Options(receiveTimeout: const Duration(minutes: 10)),
    );
    return Map<String, dynamic>.from(response.data as Map);
  }
}
