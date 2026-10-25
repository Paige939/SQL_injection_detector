import 'architecture_names.dart';

class ArchitecturePerformance {
  final String architecture;
  final int total;
  final double accuracy;
  final double precision;
  final double recall;
  final double f1;
  final int durationMs;
  final int tp, fp, tn, fn;
  final double fpr, threshold;
  final double? validationF1, validationFpr;
  final int trainingSamples;
  final int? validationSamples, trainingDurationMs;
  final String evaluationMode;
  final String? datasetFingerprint, testFingerprint, protocolVersion;
  final Map<String, dynamic> bySource, byInputType;
  final String? checkpointSha256;

  String get displayName => architectureDisplayName(architecture);

  const ArchitecturePerformance({
    required this.architecture,
    required this.total,
    required this.accuracy,
    required this.precision,
    required this.recall,
    required this.f1,
    required this.durationMs,
    required this.tp,
    required this.fp,
    required this.tn,
    required this.fn,
    required this.fpr,
    required this.threshold,
    this.validationF1,
    this.validationFpr,
    required this.trainingSamples,
    this.validationSamples,
    this.trainingDurationMs,
    required this.evaluationMode,
    this.datasetFingerprint,
    this.testFingerprint,
    this.protocolVersion,
    required this.bySource,
    required this.byInputType,
    this.checkpointSha256,
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
      tp: json['tp'] as int,
      fp: json['fp'] as int,
      tn: json['tn'] as int,
      fn: json['fn'] as int,
      fpr: (json['fpr'] as num).toDouble(),
      threshold: (json['threshold'] as num).toDouble(),
      validationF1: (json['validationF1'] as num?)?.toDouble(),
      validationFpr: (json['validationFpr'] as num?)?.toDouble(),
      trainingSamples: json['trainingSamples'] as int,
      validationSamples: json['validationSamples'] as int?,
      trainingDurationMs: json['trainingDurationMs'] as int?,
      evaluationMode: json['evaluationMode'] as String,
      datasetFingerprint: json['datasetFingerprint'] as String?,
      testFingerprint: json['testFingerprint'] as String?,
      protocolVersion: json['protocolVersion'] as String?,
      bySource: Map<String, dynamic>.from(json['bySource'] as Map),
      byInputType: Map<String, dynamic>.from(json['byInputType'] as Map),
      checkpointSha256: json['checkpointSha256'] as String?,
    );
  }
}
