import 'dart:convert';

import 'package:dio/dio.dart';

import '../core/api_client.dart';
import '../models/rule_ablation_report.dart';

abstract interface class RuleAblationReportsRepository {
  Future<List<RuleAblationReport>> listReports();
  Future<RuleAblationReport> getReport(String id);
  Future<String> downloadReport(String id);
}

class RuleAblationReportService implements RuleAblationReportsRepository {
  final ApiClient client;
  RuleAblationReportService(this.client);
  static const _base = '/api/experiments/rule-ablation/reports';

  @override
  Future<List<RuleAblationReport>> listReports() async {
    final response = await client.dio.get(_base);
    final reports = ablationMap(response.data, 'report list')['reports'];
    if (reports is! List) {
      throw const FormatException('Malformed ablation report list.');
    }
    return reports
        .map(
          (value) => RuleAblationReport.fromJson(ablationMap(value, 'report')),
        )
        .toList(growable: false);
  }

  @override
  Future<RuleAblationReport> getReport(String id) async {
    final response = await client.dio.get('$_base/${Uri.encodeComponent(id)}');
    return RuleAblationReport.fromJson(
      ablationMap(response.data, 'report'),
      reportId: id,
    );
  }

  @override
  Future<String> downloadReport(String id) async {
    final response = await client.dio.get<List<int>>(
      '$_base/${Uri.encodeComponent(id)}/download',
      options: Options(responseType: ResponseType.bytes),
    );
    if (response.data == null) {
      throw const FormatException('Empty ablation download.');
    }
    final text = utf8.decode(response.data!);
    RuleAblationReport.fromJson(
      ablationMap(jsonDecode(text), 'report'),
      reportId: id,
    );
    return text;
  }
}
