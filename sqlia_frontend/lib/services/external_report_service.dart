import 'dart:convert';

import 'package:dio/dio.dart';

import '../core/api_client.dart';
import '../models/external_report.dart';

abstract interface class ExternalReportsRepository {
  Future<List<ExternalReport>> listReports();
  Future<ExternalReport> getReport(String id);
  Future<String> downloadReport(String id);
}

class ExternalReportService implements ExternalReportsRepository {
  final ApiClient client;

  ExternalReportService(this.client);

  @override
  Future<List<ExternalReport>> listReports() async {
    final response = await client.dio.get('/api/external/reports');
    final data = Map<String, dynamic>.from(response.data as Map);
    final reports = data['reports'];
    if (reports is! List) {
      throw const FormatException('External report list is malformed.');
    }
    return reports
        .map(
          (report) =>
              ExternalReport.fromJson(Map<String, dynamic>.from(report as Map)),
        )
        .toList(growable: false);
  }

  @override
  Future<ExternalReport> getReport(String id) async {
    final response = await client.dio.get('/api/external/reports/$id');
    return ExternalReport.fromJson(
      Map<String, dynamic>.from(response.data as Map),
    );
  }

  @override
  Future<String> downloadReport(String id) async {
    final response = await client.dio.get<List<int>>(
      '/api/external/reports/$id/download',
      options: Options(responseType: ResponseType.bytes),
    );
    final bytes = response.data;
    if (bytes == null) {
      throw const FormatException('Downloaded external report is empty.');
    }
    final decoded = utf8.decode(bytes);
    ExternalReport.fromJson(
      Map<String, dynamic>.from(jsonDecode(decoded) as Map),
    );
    return const JsonEncoder.withIndent('  ').convert(jsonDecode(decoded));
  }
}
