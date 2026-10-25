import 'architecture_names.dart';

class ArchitecturePrediction {
  final String architecture;
  final int label;
  final double confidence;
  final double benignProbability;
  final double maliciousProbability;
  final String? action;
  final List<String> matchedRules;
  final double? threshold;
  final String? modelMode, checkpointSha256;

  String get displayName => architectureDisplayName(architecture);

  const ArchitecturePrediction({
    required this.architecture,
    required this.label,
    required this.confidence,
    required this.benignProbability,
    required this.maliciousProbability,
    this.action,
    this.matchedRules = const [],
    this.threshold,
    this.modelMode,
    this.checkpointSha256,
  });

  factory ArchitecturePrediction.fromJson(Map<String, dynamic> json) {
    return ArchitecturePrediction(
      architecture: json['architecture'] as String,
      label: json['label'] as int,
      confidence: (json['confidence'] as num).toDouble(),
      benignProbability: (json['benignProbability'] as num).toDouble(),
      maliciousProbability: (json['maliciousProbability'] as num).toDouble(),
      action: json['action'] as String?,
      threshold: (json['threshold'] as num?)?.toDouble(),
      modelMode: json['modelMode'] as String?,
      checkpointSha256: json['checkpointSha256'] as String?,
      matchedRules: ((json['matchedRules'] as List?) ?? const [])
          .map((r) => (r as Map)['description'] as String)
          .toList(),
    );
  }
}
