// This class represents the result of an incremental feed operation, containing the predicted label, confidence score, model update status, and total number of samples trained. It provides a factory constructor to create an instance from a JSON map.
class IncrementalFeedResult {
  final int predictedLabel;
  final double confidence;
  final bool modelUpdated;
  final int totalTrained;

  const IncrementalFeedResult({
    required this.predictedLabel,
    required this.confidence,
    required this.modelUpdated,
    required this.totalTrained,
  });
  // Factory constructor to create an [IncrementalFeedResult] instance from a JSON map.
  factory IncrementalFeedResult.fromJson(Map<String, dynamic> json) {
    return IncrementalFeedResult(
      predictedLabel: json['predictedLabel'] as int,
      confidence: (json['confidence'] as num).toDouble(),
      modelUpdated: json['modelUpdated'] as bool,
      totalTrained: json['totalTrained'] as int,
    );
  }
}