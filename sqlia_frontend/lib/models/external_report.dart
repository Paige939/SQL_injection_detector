import 'ranking_evaluation.dart';

const externalReportMetrics = <String>[
  'accuracy',
  'precision',
  'recall',
  'f1',
  'fpr',
  'averagePrecision',
];

class ExternalReport {
  final Map<String, dynamic> json;

  ExternalReport._(this.json);

  factory ExternalReport.fromJson(Map<String, dynamic> json) {
    _requiredText(json, 'schemaVersion', expected: 'sqlia-external-report-v1');
    _requiredText(json, 'reportType', expected: 'external_generalization');
    _requiredText(json, 'runId');
    _requiredText(json, 'createdAt');
    _requiredText(
      json,
      'evaluationMode',
      expected: 'frozen_external_checkpoint',
    );
    final status = _requiredText(json, 'status');
    if (!{'running', 'failed', 'complete', 'blocked'}.contains(status)) {
      throw const FormatException('Unsupported external report status.');
    }
    if (json['blindTestEligible'] is! bool ||
        json['limitations'] is! List ||
        (json['limitations'] as List).any((item) => item is! String) ||
        (json['provenance'] != null && json['provenance'] is! Map)) {
      throw const FormatException(
        'External report is missing required metadata.',
      );
    }
    final policy = _requiredMap(json, 'policy');
    for (final key in ['retrained', 'updated', 'thresholdsRetuned']) {
      if (policy[key] is! bool || policy[key] != false) {
        throw FormatException('External report policy.$key must be false.');
      }
    }
    if (status == 'complete') {
      _requiredText(json, 'completedAt');
      _requiredMap(json, 'provenance');
      _validateDataset(_requiredMap(json, 'dataset'));
      _validateModels(json['models']);
    }
    return ExternalReport._(Map<String, dynamic>.unmodifiable(json));
  }

  String get id => json['runId'] as String;
  String get status => json['status'] as String;
  List<String> get limitations => (json['limitations'] as List).cast<String>();

  Map<String, dynamic>? get dataset {
    final value = json['dataset'];
    return value is Map ? Map<String, dynamic>.from(value) : null;
  }

  List<Map<String, dynamic>> get models {
    final value = json['models'];
    if (value is! List) return const [];
    return value
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList(growable: false);
  }
}

String _requiredText(
  Map<String, dynamic> json,
  String key, {
  String? expected,
}) {
  final value = json[key];
  if (value is! String || value.trim().isEmpty) {
    throw FormatException('External report is missing $key.');
  }
  if (expected != null && value != expected) {
    throw FormatException('External report has an unsupported $key.');
  }
  return value;
}

Map<String, dynamic> _requiredMap(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! Map) {
    throw FormatException('External report is missing $key.');
  }
  return Map<String, dynamic>.from(value);
}

void _validateDataset(Map<String, dynamic> dataset) {
  for (final key in ['source', 'fileSha256', 'auditReportSha256']) {
    _requiredText(dataset, key);
  }
  for (final key in [
    'originalRows',
    'evaluatedRows',
    'benignCount',
    'maliciousCount',
    'excludedRows',
  ]) {
    _requiredInteger(dataset, key);
  }
  if (dataset['exclusionCounts'] is! Map) {
    throw const FormatException('External report is missing exclusionCounts.');
  }
}

void _validateModels(dynamic value) {
  if (value is! List || value.isEmpty) {
    throw const FormatException('Complete report has no model results.');
  }
  final architectures = <String>{};
  for (final entry in value) {
    if (entry is! Map) {
      throw const FormatException('External report contains an invalid model.');
    }
    final model = Map<String, dynamic>.from(entry);
    final architecture = _requiredText(model, 'architecture');
    if (!architectures.add(architecture)) {
      throw const FormatException(
        'External report has duplicate architectures.',
      );
    }
    _requiredText(model, 'checkpointSha256');
    if (model['threshold'] is! num || _requiredMap(model, 'metrics').isEmpty) {
      throw const FormatException('External report is missing model metrics.');
    }
    final trainingPolicy = model['trainingPolicy'];
    if (trainingPolicy is! String && trainingPolicy is! Map) {
      throw const FormatException('External report is missing trainingPolicy.');
    }
    final metrics = _requiredMap(model, 'metrics');
    for (final key in ['total', 'tp', 'fp', 'tn', 'fn']) {
      _requiredInteger(metrics, key);
    }
    for (final key in externalReportMetrics) {
      if (metrics[key] is! num) {
        throw FormatException('External report is missing metric $key.');
      }
    }
    final intervals = _requiredMap(model, 'confidenceIntervals');
    for (final key in externalReportMetrics) {
      final interval = intervals[key];
      if (interval is! Map ||
          !(interval['lower'] == null || interval['lower'] is num) ||
          !(interval['upper'] == null || interval['upper'] is num)) {
        throw FormatException('External report is missing the $key interval.');
      }
      final values = Map<String, dynamic>.from(interval);
      _requiredInteger(values, 'validReplicates');
      _requiredInteger(values, 'undefinedReplicates');
    }
    validateRankingEvaluation(model);
  }
}

int _requiredInteger(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! int || value < 0) {
    throw FormatException('External report is missing a valid $key.');
  }
  return value;
}
