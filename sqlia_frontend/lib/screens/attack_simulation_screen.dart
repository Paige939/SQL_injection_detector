import 'dart:async';

import 'package:dio/dio.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/architecture_names.dart';
import '../models/incremental_algorithm_performance.dart';
import '../models/simulation_job.dart';
import '../providers/detection_provider.dart';

class AttackSimulationScreen extends ConsumerStatefulWidget {
  final bool extendedOnly;
  const AttackSimulationScreen({super.key, this.extendedOnly = false});

  @override
  ConsumerState<AttackSimulationScreen> createState() =>
      _AttackSimulationScreenState();
}

// The state class for [AttackSimulationScreen] that manages the selected dataset, sample size, model update option, simulation job status, and error messages.
class _AttackSimulationScreenState
    extends ConsumerState<AttackSimulationScreen> {
  final sampleController = TextEditingController(text: '100');

  // Only the held-out test split is offered to keep evaluation data separate from training data.
  final datasets = const {'Test dataset': 'data/processed/test.csv'};

  String selectedDataset = 'data/processed/test.csv';
  static const allArchitectures = {
    'ARM_PROFILE': 'Normal Profile Anomaly Detection',
    'RATIO_INCREMENTAL_ML': 'Feature Extraction + Incremental ML',
    'FULL_FLOW': 'Extended FP-Growth + Incremental ML',
    'EXTENDED_ARM': 'Extended ARM + Incremental Learning',
    'PURE_EXTENDED_ARM': 'Pure Extended ARM (Rule Only)',
  };
  static const extendedArchitectures = {
    'PURE_EXTENDED_ARM': 'Pure Extended ARM (Rule Only)',
    'EXTENDED_ARM': 'Extended ARM + Incremental Learning',
  };
  String selectedArchitecture = 'PURE_EXTENDED_ARM';
  bool updateModel = false;
  SimulationJob? job;
  Timer? pollingTimer;
  String? errorMessage;
  List<IncrementalAlgorithmPerformance> algorithmResults = [];
  bool comparingAlgorithms = false;
  String? comparisonError;

  bool get isRunning => job != null && !job!.isFinished;

  @override
  // Dispose of the resources used by the widget, including the polling timer and the sample size text controller.
  void dispose() {
    pollingTimer?.cancel();
    sampleController.dispose();
    super.dispose();
  }

  // Starts a new simulation job by sending the selected dataset path, sample size, and model update option to the API. It validates the sample size input and handles errors appropriately. If the job starts successfully, it begins polling for job status updates.
  Future<void> startSimulation() async {
    final sampleSize = int.tryParse(sampleController.text);
    // Validate the sample size input to ensure it is a positive integer. If not, set an error message in the state and return early to prevent starting the simulation with invalid input.
    if (sampleSize == null || sampleSize <= 0) {
      setState(() {
        errorMessage = 'Sample size must be a positive integer.';
      });
      return;
    }

    try {
      // Start a new simulation job using the [SimulationService] provided by Riverpod. It sends the selected dataset path, sample size, and model update option to the API. If successful, it updates the job state and starts polling for job status; if an error occurs, it sets the error message in the state.
      final startedJob = await ref
          .read(simulationServiceProvider)
          .start(
            datasetPath: selectedDataset,
            sampleSize: sampleSize,
            updateModel: selectedArchitecture == 'EXTENDED_ARM' && updateModel,
            architecture: selectedArchitecture,
          );

      if (!mounted) return;

      setState(() {
        job = startedJob;
        errorMessage = null;
      });
      // Start a periodic timer to refresh the job status every 700 milliseconds. If the job has finished, it cancels the polling timer to stop further refresh attempts.
      pollingTimer?.cancel();
      pollingTimer = Timer.periodic(
        const Duration(milliseconds: 700),
        (_) => refreshJob(),
      );
    } catch (error) {
      setState(() {
        errorMessage = error.toString();
      });
    }
  }

  // Refreshes the status of the current simulation job by fetching the latest job information from the API. If the job has finished, it cancels the polling timer. If an error occurs during the fetch, it cancels the polling timer and updates the error message in the state.
  Future<void> refreshJob() async {
    if (job == null) {
      return; // If there is no job to refresh, exit the function early.
    }
    try {
      // Fetch the latest job information from the API using the [SimulationService] provided by Riverpod. If successful, update the job state; if an error occurs, cancel the polling timer and set the error message.
      final latest = await ref.read(simulationServiceProvider).getJob(job!.id);

      if (!mounted) return; // If the widget is no longer mounted, exit the function early to avoid updating the state of an unmounted widget.
      // Update the job state with the latest information fetched from the API.
      setState(() {
        job = latest;
      });
      // If the job has finished (either completed or failed), cancel the polling timer to stop further refresh attempts.
      if (latest.isFinished) {
        pollingTimer?.cancel();
      }
    } catch (error) {
      // If an error occurs during the fetch, cancel the polling timer and update the error message in the state.
      pollingTimer?.cancel();

      if (mounted) {
        setState(() {
          errorMessage = error.toString();
        });
      }
    }
  }

  Future<void> compareIncrementalAlgorithms() async {
    final sampleSize = int.tryParse(sampleController.text);
    if (sampleSize == null || sampleSize <= 0) {
      setState(
        () => comparisonError = 'Sample size must be a positive integer.',
      );
      return;
    }
    setState(() {
      comparingAlgorithms = true;
      comparisonError = null;
    });
    try {
      final results = await ref
          .read(architectureServiceProvider)
          .compareIncrementalAlgorithms(
            datasetPath: selectedDataset,
            sampleSize: sampleSize,
            extendedOnly: widget.extendedOnly,
          );
      if (mounted) {
        setState(() {
          algorithmResults = results;
        });
      }
    } on DioException catch (exception) {
      final responseData = exception.response?.data;
      final message = responseData is Map
          ? responseData['error']?.toString()
          : null;
      if (mounted) {
        setState(
          () => comparisonError =
              message ?? exception.message ?? 'Request failed.',
        );
      }
    } catch (exception) {
      if (mounted) setState(() => comparisonError = exception.toString());
    } finally {
      if (mounted) setState(() => comparingAlgorithms = false);
    }
  }

  @override
  // Build the UI for the Attack Simulation screen, including dataset selection, sample size input, model update option, and displaying job status and results.
  Widget build(BuildContext context) {
    final result = job?.result;
    final architectures = widget.extendedOnly
        ? extendedArchitectures
        : allArchitectures;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.extendedOnly
              ? 'Extended ARM Dataset Replay'
              : 'All Architecture Dataset Replay',
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            widget.extendedOnly
                ? 'Replay the same held-out records with either Pure Extended ARM or Extended ARM + Incremental Learning. Online feedback is available only for the hybrid method.'
                : 'Evaluate each of the five detection architectures on the held-out dataset. Incremental architectures can optionally learn from verified labels during replay.',
          ),
          const Text(
            'Replay uses the current website models. Enabling updates changes later '
            'website predictions for this server session. Saved research models and '
            'reports are not changed; replay is not a final independent evaluation.',
          ),
          const SizedBox(height: 16),
          DropdownButtonFormField<String>(
            initialValue: selectedDataset,
            decoration: const InputDecoration(
              labelText: 'Labeled dataset',
              border: OutlineInputBorder(),
            ),
            items: datasets.entries
                .map(
                  (entry) => DropdownMenuItem(
                    value: entry.value,
                    child: Text(entry.key),
                  ),
                )
                .toList(),
            onChanged: isRunning
                ? null
                : (value) => setState(() {
                    selectedDataset = value!;
                  }),
          ),
          const SizedBox(height: 16),
          DropdownButtonFormField<String>(
            initialValue: selectedArchitecture,
            decoration: const InputDecoration(
              labelText: 'Extended ARM method',
              border: OutlineInputBorder(),
            ),
            items: architectures.entries
                .map(
                  (entry) => DropdownMenuItem(
                    value: entry.key,
                    child: Text(entry.value),
                  ),
                )
                .toList(),
            onChanged: isRunning
                ? null
                : (value) {
                    if (value == null) return;
                    setState(() {
                      selectedArchitecture = value;
                      if (value == 'PURE_EXTENDED_ARM') updateModel = false;
                    });
                  },
          ),
          const SizedBox(height: 12),
          TextField(
            controller: sampleController,
            enabled: !isRunning,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Number of records to replay',
              border: OutlineInputBorder(),
            ),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Update the incremental model during replay'),
            subtitle: Text(
              selectedArchitecture == 'PURE_EXTENDED_ARM'
                  ? 'Pure Extended ARM uses fixed mined rules and cannot be updated online.'
                  : 'After each prediction, use the verified label to update the incremental classifier. Later predictions in this replay may change.',
            ),
            value: updateModel,
            onChanged: isRunning || selectedArchitecture == 'PURE_EXTENDED_ARM'
                ? null
                : (value) => setState(() {
                    updateModel = value;
                  }),
          ),
          FilledButton.icon(
            onPressed: isRunning ? null : startSimulation,
            icon: const Icon(Icons.play_arrow),
            label: const Text('Evaluate selected Extended ARM method'),
          ),
          const SizedBox(height: 28),
          const Divider(),
          const SizedBox(height: 12),
          Text(
            'Compare Incremental Learning algorithms',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          Text(
            widget.extendedOnly
                ? 'Compare three online learners using the Extended ARM feature set.'
                : 'Compare three online learners for every architecture in this mode that uses Incremental Learning.',
          ),
          const SizedBox(height: 8),
          const Text(
            'Each learner warms up on the same training sample. During replay, each query is first evaluated and then used as labeled feedback to update that learner. The three isolated models see the same held-out query order; this comparison does not alter the running detector model.',
          ),
          const SizedBox(height: 12),
          FilledButton.tonalIcon(
            onPressed: comparingAlgorithms || isRunning
                ? null
                : compareIncrementalAlgorithms,
            icon: const Icon(Icons.query_stats),
            label: const Text('Run online algorithm comparison'),
          ),
          if (comparingAlgorithms)
            const Padding(
              padding: EdgeInsets.only(top: 12),
              child: LinearProgressIndicator(),
            ),
          if (comparisonError != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                comparisonError!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          for (final architecture
              in algorithmResults
                  .map((result) => result.architecture)
                  .toSet()) ...[
            const SizedBox(height: 20),
            _IncrementalAlgorithmChart(
              architecture: architecture,
              results: algorithmResults
                  .where((result) => result.architecture == architecture)
                  .toList(),
            ),
            for (final result in algorithmResults.where(
              (result) => result.architecture == architecture,
            ))
              _IncrementalAlgorithmResultCard(result: result),
          ],
          if (job != null) ...[
            // Display job status and progress if a job is running or has completed
            const SizedBox(height: 24),
            Text('Replay status: ${_statusLabel(job!.status)}'),
            const SizedBox(height: 8),
            LinearProgressIndicator(value: job!.progress),
            const SizedBox(height: 8),
            Text('${job!.processed} of ${job!.total} records processed'),
          ],
          if (job?.status == 'FAILED') ...[
            // Display error message if the job failed
            const SizedBox(height: 16),
            Text(
              job!.error ?? 'Simulation failed.',
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          if (errorMessage != null) ...[
            // Display error message if any
            const SizedBox(height: 16),
            Text(
              errorMessage!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          if (result != null) ...[
            // Display simulation results if available
            const SizedBox(height: 28),
            Text(
              'Replay evaluation results',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Text('Accuracy: ${(result.accuracy * 100).toStringAsFixed(2)}%'),
            Text('Precision: ${(result.precision * 100).toStringAsFixed(2)}%'),
            Text('Recall: ${(result.recall * 100).toStringAsFixed(2)}%'),
            Text('F1 score: ${(result.f1 * 100).toStringAsFixed(2)}%'),
            const SizedBox(height: 16),
            Text(
              'True positives: ${result.tp}    False positives: ${result.fp}',
            ),
            Text(
              'True negatives: ${result.tn}    False negatives: ${result.fn}',
            ),
            Text('Replay time: ${result.durationMs} ms'),
          ],
        ],
      ),
    );
  }

  String _statusLabel(String status) => switch (status) {
    'RUNNING' => 'Running',
    'COMPLETED' => 'Completed',
    'FAILED' => 'Failed',
    _ => status,
  };
}

const _algorithmColors = [Colors.blue, Colors.deepOrange, Colors.green];

class _IncrementalAlgorithmChart extends StatelessWidget {
  final String architecture;
  final List<IncrementalAlgorithmPerformance> results;

  const _IncrementalAlgorithmChart({
    required this.architecture,
    required this.results,
  });

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        '${architectureDisplayName(architecture)} — cumulative accuracy',
        style: Theme.of(context).textTheme.titleMedium,
      ),
      const SizedBox(height: 8),
      const Text('X-axis: processed test queries  |  Y-axis: accuracy'),
      SizedBox(
        height: 280,
        child: LineChart(
          LineChartData(
            minY: 0,
            maxY: 1,
            gridData: const FlGridData(show: true),
            borderData: FlBorderData(show: true),
            titlesData: const FlTitlesData(
              topTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
              rightTitles: AxisTitles(
                sideTitles: SideTitles(showTitles: false),
              ),
            ),
            lineBarsData: [
              for (var index = 0; index < results.length; index++)
                LineChartBarData(
                  spots: [
                    for (final point in results[index].accuracyHistory)
                      FlSpot(point.processed.toDouble(), point.accuracy),
                  ],
                  color: _algorithmColors[index],
                  dotData: const FlDotData(show: false),
                  isCurved: false,
                ),
            ],
          ),
        ),
      ),
      Wrap(
        spacing: 16,
        runSpacing: 8,
        children: [
          for (var index = 0; index < results.length; index++)
            _AlgorithmLegend(
              color: _algorithmColors[index],
              title: results[index].displayName,
            ),
        ],
      ),
      const SizedBox(height: 12),
    ],
  );
}

class _AlgorithmLegend extends StatelessWidget {
  final Color color;
  final String title;

  const _AlgorithmLegend({required this.color, required this.title});

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(width: 12, height: 12, color: color),
      const SizedBox(width: 6),
      Text(title),
    ],
  );
}

class _IncrementalAlgorithmResultCard extends StatelessWidget {
  final IncrementalAlgorithmPerformance result;

  const _IncrementalAlgorithmResultCard({required this.result});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            result.displayName,
            style: Theme.of(context).textTheme.titleSmall,
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 14,
            runSpacing: 8,
            children: [
              Text('Accuracy: ${(result.accuracy * 100).toStringAsFixed(1)}%'),
              Text(
                'Precision: ${(result.precision * 100).toStringAsFixed(1)}%',
              ),
              Text('Recall: ${(result.recall * 100).toStringAsFixed(1)}%'),
              Text('F1: ${(result.f1 * 100).toStringAsFixed(1)}%'),
              Text('Predictions + updates: ${result.durationMs} ms'),
            ],
          ),
        ],
      ),
    ),
  );
}
