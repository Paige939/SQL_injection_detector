import 'external_report.dart';
import 'ranking_evaluation.dart';

const rulePolicies = ['BASELINE', 'NO_NEGATIVE', 'SQL_CONTEXT'];
const ruleArchitectures = ['PURE_EXTENDED_ARM', 'EXTENDED_ARM'];

Map<String, dynamic> ablationMap(dynamic value, String field) {
  if (value is! Map) throw FormatException('Missing or invalid $field.');
  return Map<String, dynamic>.from(value);
}

String _text(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String || value.trim().isEmpty) {
    throw FormatException('Missing or invalid $key.');
  }
  return value;
}

int _integer(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! int || value < 0) {
    throw FormatException('Missing or invalid $key.');
  }
  return value;
}

void _hash(Map<String, dynamic> json, String key) {
  if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(_text(json, key))) {
    throw FormatException('Invalid $key.');
  }
}

bool _unit(dynamic value) =>
    value is num && value.isFinite && value >= 0 && value <= 1;

class RuleAblationReport {
  final String id;
  final Map<String, dynamic> json;
  RuleAblationReport._(this.id, this.json);

  factory RuleAblationReport.fromJson(
    Map<String, dynamic> json, {
    String? reportId,
  }) {
    final id = reportId ?? _text(json, 'runId');
    if (!RegExp(r'^rule-ablation-[A-Za-z0-9][A-Za-z0-9._-]*$').hasMatch(id) ||
        id.contains('..') ||
        (json.containsKey('runId') && json['runId'] != id)) {
      throw const FormatException('Invalid rule ablation report ID.');
    }
    if (json['schemaVersion'] != 'sqlia-rule-ablation-v1' ||
        !['running', 'failed', 'complete'].contains(json['status'])) {
      throw const FormatException('Unsupported rule ablation report.');
    }
    _text(json, 'createdAt');
    if (json['status'] == 'complete') {
      _text(json, 'completedAt');
      _hash(json, 'frozenManifestSha256');
      final protocol = ablationMap(json['protocol'], 'protocol');
      if (protocol['websiteModelPromoted'] != false ||
          protocol['oldFinalTestRead'] != false) {
        throw const FormatException(
          'Experiment must not be deployed or read final Test.',
        );
      }
      for (final key in [
        'thresholdSelection',
        'externalUsage',
        'candidateControl',
        'sqlContext',
        'bootstrap',
      ]) {
        _text(protocol, key);
      }
      final limitations = protocol['limitations'];
      if (limitations is! List ||
          limitations.any((item) => item is! String || item.trim().isEmpty)) {
        throw const FormatException('Invalid limitations.');
      }
      final seeds = protocol['seeds'];
      if (seeds is! List ||
          seeds.length < 2 ||
          seeds.any((s) => s is! int) ||
          seeds.toSet().length != seeds.length) {
        throw const FormatException('Invalid experiment seeds.');
      }
      for (final entry in {
        'policies': rulePolicies,
        'architectures': ruleArchitectures,
      }.entries) {
        final names = protocol[entry.key];
        if (names is! List ||
            names.length != entry.value.length ||
            names.toSet().length != names.length ||
            !names.toSet().containsAll(entry.value)) {
          throw FormatException('Invalid declared ${entry.key}.');
        }
      }
      final inputs = ablationMap(
        json['verifiedInputSha256'],
        'input provenance',
      );
      if (inputs.isEmpty) {
        throw const FormatException('Missing input provenance.');
      }
      for (final key in inputs.keys) {
        _hash(inputs, key);
      }
      final trials = json['trials'];
      if (trials is! List ||
          trials.length != seeds.length * rulePolicies.length) {
        throw const FormatException('Incomplete policy/seed matrix.');
      }
      final pairs = <String>{};
      final ids = <String>{};
      int? trainingRows;
      int? externalRows;
      for (final value in trials) {
        final trial = ablationMap(value, 'trial');
        final policy = _text(trial, 'extendedRulePolicy');
        if (!rulePolicies.contains(policy) ||
            !seeds.contains(trial['seed']) ||
            !pairs.add('$policy:${trial['seed']}') ||
            !ids.add(_text(trial, 'id'))) {
          throw const FormatException(
            'Duplicate or unsupported policy/seed trial.',
          );
        }
        _hash(trial, 'checkpointSha256');
        _text(trial, 'checkpoint');
        final rows = _integer(trial, 'trainingRows');
        if (rows == 0 || (trainingRows != null && rows != trainingRows)) {
          throw const FormatException('Inconsistent training rows.');
        }
        trainingRows = rows;
        final count = _integer(trial, 'extendedRuleCount');
        if (trial['zeroExtendedRules'] != (count == 0) ||
            trial['extendedRules'] is! List ||
            (trial['extendedRules'] as List).length != count) {
          throw const FormatException('Invalid rule schema count.');
        }
        for (final field in [
          'architectures',
          'externalDevelopmentDiagnostics',
        ]) {
          final results = ablationMap(trial[field], field);
          if (results.length != ruleArchitectures.length) {
            throw const FormatException('Incomplete architecture results.');
          }
          for (final architecture in ruleArchitectures) {
            final result = ablationMap(results[architecture], architecture);
            _validateResult(result);
            if (field == 'externalDevelopmentDiagnostics') {
              final validation = ablationMap(
                ablationMap(
                  trial['architectures'],
                  'architectures',
                )[architecture],
                'Validation result',
              );
              if (result['threshold'] != validation['threshold']) {
                throw const FormatException(
                  'External threshold differs from Validation.',
                );
              }
              final total =
                  ablationMap(result['metrics'], 'metrics')['total'] as int;
              if (externalRows != null && externalRows != total) {
                throw const FormatException('Inconsistent external rows.');
              }
              externalRows = total;
            }
          }
        }
      }
    }
    return RuleAblationReport._(id, Map.unmodifiable(json));
  }

  String get status => json['status'] as String;
  List<RuleAblationTrial> get trials => (json['trials'] as List)
      .map((value) => RuleAblationTrial(ablationMap(value, 'trial')))
      .toList(growable: false);
  Map<String, dynamic> get protocol =>
      ablationMap(json['protocol'], 'protocol');
}

class RuleAblationTrial {
  final Map<String, dynamic> json;
  RuleAblationTrial(this.json);
  String get policy => json['extendedRulePolicy'] as String;
  int get seed => json['seed'] as int;
  Map<String, dynamic> result(String architecture, {bool external = true}) =>
      ablationMap(
        ablationMap(
          json[external ? 'externalDevelopmentDiagnostics' : 'architectures'],
          'results',
        )[architecture],
        architecture,
      );
  Map<String, dynamic> metrics(String architecture, {bool external = true}) =>
      ablationMap(
        result(architecture, external: external)['metrics'],
        'metrics',
      );
}

void _validateResult(Map<String, dynamic> result) {
  final threshold = result['threshold'];
  // Existing selector can use the next representable value above 1.
  if (threshold is! num ||
      !threshold.isFinite ||
      threshold < 0 ||
      threshold > 1.0000000000000002) {
    throw const FormatException('Invalid threshold.');
  }
  final metrics = ablationMap(result['metrics'], 'metrics');
  final total = _integer(metrics, 'total');
  final sum = [
    'tp',
    'fp',
    'tn',
    'fn',
  ].fold<int>(0, (sum, key) => sum + _integer(metrics, key));
  if (total == 0 || sum != total) {
    throw const FormatException('Confusion counts do not sum to total.');
  }
  final intervals = ablationMap(
    result['confidenceIntervals'],
    'confidenceIntervals',
  );
  for (final key in externalReportMetrics) {
    if (!metrics.containsKey(key) ||
        !(metrics[key] == null || _unit(metrics[key]))) {
      throw FormatException('Missing or invalid metric $key.');
    }
    final interval = ablationMap(intervals[key], '$key interval');
    final lo = interval['lower'], hi = interval['upper'];
    if (!interval.containsKey('lower') ||
        !interval.containsKey('upper') ||
        !((lo == null && hi == null) ||
            (_unit(lo) && _unit(hi) && (lo as num) <= (hi as num)))) {
      throw const FormatException('Invalid interval bounds.');
    }
    final valid = _integer(interval, 'validReplicates');
    final undefined = _integer(interval, 'undefinedReplicates');
    if (valid + undefined < 200 ||
        valid + undefined > 2000 ||
        (valid == 0) != (lo == null)) {
      throw const FormatException('Invalid bootstrap counts.');
    }
  }
  validateRankingEvaluation(result);
}
