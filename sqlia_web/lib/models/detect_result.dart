// A class representing the result of a detection.
class DetectResult{
  final int label;
  final double confidence;
  final Map<String, double> features;
  // Creates an instance of [DetectResult] with the specified [label], [confidence], and [features].
  DetectResult({required this.label, required this.confidence, required this.features});
  factory DetectResult.fromJson(Map<String, dynamic> json) {
    // Convert the 'features' map from dynamic to Map<String, double>
    final rawFeatures = Map<String, dynamic>.from(json['features'] as Map);
    // Create a new DetectResult instance using the parsed values
    return DetectResult(
      label: json['label'] as int,
      confidence: (json['confidence'] as num).toDouble(),
      features: rawFeatures.map((key, value) => MapEntry(key, value.toDouble())),
    );
  }
}