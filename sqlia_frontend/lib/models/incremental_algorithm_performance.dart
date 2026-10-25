class IncrementalAlgorithmPerformance {
  final String architecture;
  final String algorithm;
  final String displayName;
  final int total;
  final double accuracy;
  final double precision;
  final double recall;
  final double f1;
  final int durationMs;
  final int warmupSamples;
  final List<IncrementalEvaluationPoint> accuracyHistory;

  const IncrementalAlgorithmPerformance({
    required this.architecture,
    required this.algorithm,
    required this.displayName,
    required this.total,
    required this.accuracy,
    required this.precision,
    required this.recall,
    required this.f1,
    required this.durationMs,
    required this.warmupSamples,
    required this.accuracyHistory,
  });

  factory IncrementalAlgorithmPerformance.fromJson(Map<String, dynamic> json) {
    return IncrementalAlgorithmPerformance(
      architecture: json['architecture'] as String,
      algorithm: json['algorithm'] as String,
      displayName: json['displayName'] as String,
      total: json['total'] as int,
      accuracy: (json['accuracy'] as num).toDouble(),
      precision: (json['precision'] as num).toDouble(),
      recall: (json['recall'] as num).toDouble(),
      f1: (json['f1'] as num).toDouble(),
      durationMs: json['durationMs'] as int,
      warmupSamples: json['warmupSamples'] as int,
      accuracyHistory: (json['accuracyHistory'] as List)
          .map(
            (value) => IncrementalEvaluationPoint.fromJson(
              Map<String, dynamic>.from(value as Map),
            ),
          )
          .toList(),
    );
  }
}

class IncrementalEvaluationPoint {
  final int processed;
  final double accuracy;

  const IncrementalEvaluationPoint({
    required this.processed,
    required this.accuracy,
  });

  factory IncrementalEvaluationPoint.fromJson(Map<String, dynamic> json) {
    return IncrementalEvaluationPoint(
      processed: json['processed'] as int,
      accuracy: (json['accuracy'] as num).toDouble(),
    );
  }
}
