import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api_client.dart';
import '../services/detection_service.dart';
import '../models/detect_result.dart';
import '../services/simulation_service.dart';
import '../services/rule_service.dart';
import '../services/incremental_service.dart';
import '../services/architecture_service.dart';

// A Riverpod provider that creates an instance of [ApiClient].
final apiClientProvider = Provider((ref) => ApiClient());
// A Riverpod provider that creates an instance of [DetectionService] using the [ApiClient] provided by [apiClientProvider].
final detectionServiceProvider = Provider(
  (ref) => DetectionService(ref.watch(apiClientProvider)),
);
// A Riverpod provider that fetches a [DetectResult] based on the provided SQL string.
final detectResultProvider = FutureProvider.family<DetectResult, String>((
  ref,
  sql,
) {
  return ref.watch(detectionServiceProvider).detect(sql);
});
// A Riverpod provider that creates an instance of [SimulationService] using the [ApiClient] provided by [apiClientProvider].
final simulationServiceProvider = Provider(
  (ref) => SimulationService(ref.watch(apiClientProvider)),
);
// A Riverpod provider that fetches a [SimulationResult] based on the provided dataset path, sample size, and model update option.
final ruleServiceProvider = Provider(
  (ref) => RuleService(ref.watch(apiClientProvider)),
);
// A Riverpod provider that creates an instance of [IncrementalService] using the [ApiClient] provided by [apiClientProvider].
final incrementalServiceProvider = Provider(
  (ref) => IncrementalService(ref.watch(apiClientProvider)),
);
final architectureServiceProvider = Provider(
  (ref) => ArchitectureService(ref.watch(apiClientProvider)),
);
