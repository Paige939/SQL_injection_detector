import 'simulation_result.dart';
// Represents a simulation job, including its ID, status, processed and total counts, any error message, and the result of the simulation if available.
class SimulationJob {
  final String id;
  final String status;
  final int processed;
  final int total;
  final String? error;
  final SimulationResult? result;

  const SimulationJob({
    required this.id,
    required this.status,
    required this.processed,
    required this.total,
    this.error,
    this.result,
  });
  // Checks if the simulation job has finished, either successfully or with a failure, based on its status.
  bool get isFinished => status == 'COMPLETED' || status == 'FAILED';
  // Calculates the progress of the simulation job as a double value between 0 and 1, representing the ratio of processed items to total items. If the total is zero, it returns 0 to avoid division by zero.
  double get progress {
    if (total == 0) return 0;
    return processed / total;
  }
  // Factory constructor to create a [SimulationJob] instance from a JSON map.
  factory SimulationJob.fromJson(Map<String, dynamic> json) {
    return SimulationJob(
      id: json['id'] as String,
      status: json['status'] as String,
      processed: (json['processed'] as num?)?.toInt() ?? 0,
      total: (json['total'] as num?)?.toInt() ?? 0,
      error: json['error'] as String?,
      result: json['result'] == null
          ? null
          : SimulationResult.fromJson(
              Map<String, dynamic>.from(json['result'] as Map),
            ),
    );
  }
}