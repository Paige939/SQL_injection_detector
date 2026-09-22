// Represents a point in time with associated accuracy metrics, including the index, accuracy value, total evaluated instances, and timestamp.
class AccuracyPoint{
  final int index; // The index of the accuracy point, representing its position in a sequence of accuracy measurements.
  final double accuracy; // The accuracy value at this point in time.
  final int totalEvaluated; // The total number of evaluated instances at this point in time.
  final DateTime timeStamp; // The timestamp indicating when this accuracy point was recorded.
  // Constructs an [AccuracyPoint] instance with the specified index, accuracy, total evaluated count, and timestamp.
  const AccuracyPoint({
    required this.index,
    required this.accuracy,
    required this.totalEvaluated,
    required this.timeStamp
  });
  // Factory constructor to create an [AccuracyPoint] instance from a JSON map.
  factory AccuracyPoint.fromJson(Map<String, dynamic> json){
    // Creates an [AccuracyPoint] instance from a JSON map by extracting the index, accuracy, total evaluated count, and timestamp, converting them to the appropriate types.
    return AccuracyPoint(
      index: json['index'] as int,
      accuracy: (json['accuracy'] as num).toDouble(),
      totalEvaluated: json['totalEvaluated'] as int,
      timeStamp: DateTime.parse(json['timeStamp'] as String)
    );
  }
}