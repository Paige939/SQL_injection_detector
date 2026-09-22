class ArchitecturePerformance {
  final String architecture;
  final int total;
  final double accuracy;
  final double precision;
  final double recall;
  final double f1;
  final int durationMs;

  const ArchitecturePerformance({
    required this.architecture,
    required this.total,
    required this.accuracy,
    required this.precision,
    required this.recall,
    required this.f1,
    required this.durationMs,
  });

  factory ArchitecturePerformance.fromJson(Map<String, dynamic> json) {
    return ArchitecturePerformance(
      architecture: json['architecture'] as String,
      total: json['total'] as int,
      accuracy: (json['accuracy'] as num).toDouble(),
      precision: (json['precision'] as num).toDouble(),
      recall: (json['recall'] as num).toDouble(),
      f1: (json['f1'] as num).toDouble(),
      durationMs: json['durationMs'] as int,
    );
  }
}
