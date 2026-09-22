import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/simulation_job.dart';
import '../providers/detection_provider.dart';

class AttackSimulationScreen extends ConsumerStatefulWidget {
  const AttackSimulationScreen({super.key});

  @override
  ConsumerState<AttackSimulationScreen> createState() =>
      _AttackSimulationScreenState();
}
// The state class for [AttackSimulationScreen] that manages the selected dataset, sample size, model update option, simulation job status, and error messages.
class _AttackSimulationScreenState
    extends ConsumerState<AttackSimulationScreen> {
  final sampleController = TextEditingController(text: '100');

  final datasets = const {
    'Modified SQL Dataset': 'data/raw/Modified_SQL_Dataset.csv',
    'SQLi Dataset': 'data/raw/sqli.csv',
    'SQLiV3 Dataset': 'data/raw/SQLiV3.csv',
  };

  String selectedDataset = 'data/raw/Modified_SQL_Dataset.csv';
  bool updateModel = false;
  SimulationJob? job;
  Timer? pollingTimer;
  String? errorMessage;

  bool get isRunning =>
      job != null && !job!.isFinished;

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

    try { // Start a new simulation job using the [SimulationService] provided by Riverpod. It sends the selected dataset path, sample size, and model update option to the API. If successful, it updates the job state and starts polling for job status; if an error occurs, it sets the error message in the state.
      final startedJob = await ref
          .read(simulationServiceProvider)
          .start(
            datasetPath: selectedDataset,
            sampleSize: sampleSize,
            updateModel: updateModel,
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
    if (job == null) return; // If there is no job to refresh, exit the function early.

    try { // Fetch the latest job information from the API using the [SimulationService] provided by Riverpod. If successful, update the job state; if an error occurs, cancel the polling timer and set the error message.
      final latest = await ref
          .read(simulationServiceProvider)
          .getJob(job!.id);

      if (!mounted) return; // If the widget is no longer mounted, exit the function early to avoid updating the state of an unmounted widget.
      // Update the job state with the latest information fetched from the API.
      setState(() {
        job = latest;
      });
      // If the job has finished (either completed or failed), cancel the polling timer to stop further refresh attempts.
      if (latest.isFinished) {
        pollingTimer?.cancel();
      }
    } catch (error) { // If an error occurs during the fetch, cancel the polling timer and update the error message in the state.
      pollingTimer?.cancel();

      if (mounted) {
        setState(() {
          errorMessage = error.toString();
        });
      }
    }
  }

  @override
  // Build the UI for the Attack Simulation screen, including dataset selection, sample size input, model update option, and displaying job status and results.
  Widget build(BuildContext context) {
    final result = job?.result;

    return Scaffold(
      appBar: AppBar(title: const Text('Attack Simulation')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          DropdownButtonFormField<String>(
            initialValue: selectedDataset,
            decoration: const InputDecoration(
              labelText: 'Dataset',
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
          TextField(
            controller: sampleController,
            enabled: !isRunning,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Sample size',
              border: OutlineInputBorder(),
            ),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Update incremental model'),
            value: updateModel,
            onChanged: isRunning
                ? null
                : (value) => setState(() {
                      updateModel = value;
                    }),
          ),
          FilledButton.icon(
            onPressed: isRunning ? null : startSimulation,
            icon: const Icon(Icons.play_arrow),
            label: const Text('Start simulation'),
          ),
          if (job != null) ...[ // Display job status and progress if a job is running or has completed
            const SizedBox(height: 24),
            Text('Status: ${job!.status}'),
            const SizedBox(height: 8),
            LinearProgressIndicator(value: job!.progress),
            const SizedBox(height: 8),
            Text('${job!.processed} / ${job!.total} records processed'),
          ],
          if (job?.status == 'FAILED') ...[ // Display error message if the job failed
            const SizedBox(height: 16),
            Text(
              job!.error ?? 'Simulation failed.',
              style: TextStyle(
                color: Theme.of(context).colorScheme.error,
              ),
            ),
          ], 
          if (errorMessage != null) ...[ // Display error message if any
            const SizedBox(height: 16),
            Text(
              errorMessage!,
              style: TextStyle(
                color: Theme.of(context).colorScheme.error,
              ),
            ),
          ],
          if (result != null) ...[ // Display simulation results if available
            const SizedBox(height: 28),
            Text(
              'Simulation Result',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 12),
            Text('Accuracy: ${(result.accuracy * 100).toStringAsFixed(2)}%'),
            Text('Precision: ${(result.precision * 100).toStringAsFixed(2)}%'),
            Text('Recall: ${(result.recall * 100).toStringAsFixed(2)}%'),
            Text('F1 Score: ${(result.f1 * 100).toStringAsFixed(2)}%'),
            const SizedBox(height: 16),
            Text('TP: ${result.tp}    FP: ${result.fp}'),
            Text('TN: ${result.tn}    FN: ${result.fn}'),
            Text('Duration: ${result.durationMs} ms'),
          ],
        ],
      ),
    );
  }
}