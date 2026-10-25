import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/incremental_feed_result.dart';
import '../providers/detection_provider.dart';
import '../core/detection_input.dart';

class RatioMlScreen extends ConsumerStatefulWidget {
  const RatioMlScreen({super.key});

  @override
  ConsumerState<RatioMlScreen> createState() => _RatioMlScreenState();
}

class _RatioMlScreenState extends ConsumerState<RatioMlScreen> {
  final controller = TextEditingController();
  final labelController = TextEditingController();
  static const algorithms = {
    'CURRENT_ONLINE_BAGGING_NB': 'Current Online Bagging (Naive Bayes)',
    'SGD': 'SGD / Incremental SVM',
    'HOEFFDING_TREE': 'Hoeffding Tree',
  };
  String selectedAlgorithm = 'CURRENT_ONLINE_BAGGING_NB';
  IncrementalFeedResult? result;
  String? error;
  bool loading = false;

  @override
  void dispose() {
    controller.dispose();
    labelController.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    final sql = controller.text;
    final inputError = validateDetectionInput(sql);
    final labelText = labelController.text.trim();
    final label = labelText.isEmpty ? null : int.tryParse(labelText);
    if (inputError != null ||
        (labelText.isNotEmpty &&
            (label == null || (label != 0 && label != 1)))) {
      setState(
        () =>
            error = inputError ?? 'Enter an optional verified label of 0 or 1.',
      );
      return;
    }
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final value = await ref
          .read(incrementalServiceProvider)
          .feed(sql: sql, label: label, modelId: selectedAlgorithm);
      if (mounted) setState(() => result = value);
    } catch (value) {
      if (mounted) setState(() => error = value.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final malicious = result?.predictedLabel == 1;
    return Scaffold(
      appBar: AppBar(title: const Text('Online Classification and Feedback')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Classify one input with an online model',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'Choose a model to classify the input. Leave the label blank to predict only; enter a verified label to record the result and train the selected model.',
          ),
          const SizedBox(height: 8),
          const Text(detectionDisclaimer),
          const Text(
            'Verified feedback updates the running website model and affects later predictions. '
            'Online changes last for this server session; restarting rebuilds the models from Training. '
            'Saved research models and reports are not changed.',
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<String>(
            initialValue: selectedAlgorithm,
            decoration: const InputDecoration(
              labelText: 'Online classification model',
              border: OutlineInputBorder(),
            ),
            items: algorithms.entries
                .map(
                  (entry) => DropdownMenuItem(
                    value: entry.key,
                    child: Text(entry.value),
                  ),
                )
                .toList(),
            onChanged: loading
                ? null
                : (value) {
                    if (value == null) return;
                    setState(() {
                      selectedAlgorithm = value;
                      result = null;
                    });
                  },
          ),
          const SizedBox(height: 12),
          TextField(
            controller: controller,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: detectionInputLabel,
              hintText: detectionInputHint,
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: labelController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText:
                  'Verified label (optional: 0 = benign, 1 = SQL injection)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loading ? null : submit,
            icon: const Icon(Icons.bolt),
            label: const Text('Classify input'),
          ),
          if (loading)
            const Padding(
              padding: EdgeInsets.only(top: 16),
              child: LinearProgressIndicator(),
            ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Text(
                error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if (result != null)
            Padding(
              padding: const EdgeInsets.only(top: 24),
              child: Card(
                color: malicious ? Colors.red.shade50 : Colors.green.shade50,
                child: ListTile(
                  leading: Icon(
                    malicious ? Icons.warning_amber : Icons.check_circle,
                    color: malicious ? Colors.red : Colors.green,
                  ),
                  title: Text(
                    malicious ? 'SQLi prediction' : 'Benign prediction',
                  ),
                  subtitle: Text(
                    'Model: ${algorithms[result!.modelId] ?? result!.modelId}\n'
                    'Class score: ${(result!.confidence * 100).toStringAsFixed(1)}% (not calibrated)\n'
                    '${result!.threshold == null ? '' : 'SQLi score: ${result!.score} | Threshold: ${result!.threshold}\n'}'
                    '${result!.modelMode == null ? '' : 'Mode: ${result!.modelMode}\n'}'
                    'Model updated: ${result!.modelUpdated ? 'Yes' : 'No'}\n'
                    'Training samples: ${result!.totalTrained}',
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
