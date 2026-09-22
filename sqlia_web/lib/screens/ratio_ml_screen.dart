import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/incremental_feed_result.dart';
import '../providers/detection_provider.dart';

class RatioMlScreen extends ConsumerStatefulWidget {
  const RatioMlScreen({super.key});

  @override
  ConsumerState<RatioMlScreen> createState() => _RatioMlScreenState();
}

class _RatioMlScreenState extends ConsumerState<RatioMlScreen> {
  final controller = TextEditingController();
  final labelController = TextEditingController();
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
    final sql = controller.text.trim();
    final labelText = labelController.text.trim();
    final label = labelText.isEmpty ? null : int.tryParse(labelText);
    if (sql.isEmpty ||
        (labelText.isNotEmpty &&
            (label == null || (label != 0 && label != 1)))) {
      setState(
        () => error = 'Enter a request and, optionally, a label of 0 or 1.',
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
          .feed(sql: sql, label: label);
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
      appBar: AppBar(title: const Text('Feature Ratio + Incremental ML')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Ratio feature classifier',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'The request is transformed into six base SQL features, then evaluated by the online incremental classifier.',
          ),
          const SizedBox(height: 20),
          TextField(
            controller: controller,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: 'SQL or HTTP request',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: labelController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Verified label (optional: 0 or 1)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loading ? null : submit,
            icon: const Icon(Icons.bolt),
            label: const Text('Predict and update'),
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
                    'Confidence: ${(result!.confidence * 100).toStringAsFixed(1)}%\n'
                    'ML updated: ${result!.modelUpdated}\n'
                    'Total trained: ${result!.totalTrained}',
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
