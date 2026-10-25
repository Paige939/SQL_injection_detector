// Represents the result of a simulation, including counts of true positives, false positives, true negatives, false negatives, accuracy, precision, recall, F1 score, and duration in milliseconds.
class SimulationResult{
  final int total;
  final int tp;
  final int tn;
  final int fp;
  final int fn;
  final double accuracy;
  final double precision;
  final double recall;
  final double f1;
  final int durationMs;

  // Constructs a [SimulationResult] instance with the specified parameters.
  const SimulationResult({
    required this.total, 
    required this.tp,
    required this.tn,
    required this.fp,
    required this.fn,
    required this.accuracy,
    required this.precision,
    required this.recall,
    required this.f1,
    required this.durationMs
  });
  // Factory constructor to create a [SimulationResult] instance from a JSON map.
  factory SimulationResult.fromJson(Map<String, dynamic> json){
    return SimulationResult(
      total: json['total'] as int,
      tp: json['tp'] as int,
      tn: json['tn'] as int,
      fp: json['fp'] as int,
      fn: json['fn'] as int,
      accuracy: (json['accuracy'] as num).toDouble(),
      precision: (json['precision'] as num).toDouble(),
      recall: (json['recall'] as num).toDouble(),
      f1: (json['f1'] as num).toDouble(),
      durationMs: json['durationMs'] as int
    );
  }
}