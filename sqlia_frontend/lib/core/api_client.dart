import 'package:dio/dio.dart';

// A simple API client that uses Dio for making HTTP requests.
class ApiClient {
  final Dio dio;
  // Creates an instance of [ApiClient] with the specified [baseUrl].
  ApiClient({
    String baseUrl = const String.fromEnvironment(
      'API_BASE_URL',
      defaultValue: 'http://localhost:8080',
    ),
  }) : dio = Dio(
         BaseOptions(
           baseUrl: baseUrl,
           connectTimeout: const Duration(seconds: 10),
         ),
       );
}
