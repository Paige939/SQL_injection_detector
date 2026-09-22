class ArchitecturePrediction {
  final String architecture;
  final int label;
  final double confidence;
  final double benignProbability;
  final double maliciousProbability;

  const ArchitecturePrediction({
    required this.architecture,
    required this.label,
    required this.confidence,
    required this.benignProbability,
    required this.maliciousProbability,
  });

  factory ArchitecturePrediction.fromJson(Map<String, dynamic> json) {
    return ArchitecturePrediction(
      architecture: json['architecture'] as String,
      label: json['label'] as int,
      confidence: (json['confidence'] as num).toDouble(),
      benignProbability: (json['benignProbability'] as num).toDouble(),
      maliciousProbability: (json['maliciousProbability'] as num).toDouble(),
    );
  }
}
