import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/architecture_prediction.dart';
import '../providers/detection_provider.dart';

class ArchitectureQueryScreen extends ConsumerStatefulWidget {
  const ArchitectureQueryScreen({super.key});

  @override
  ConsumerState<ArchitectureQueryScreen> createState() =>
      _ArchitectureQueryScreenState();
}

class _ArchitectureQueryScreenState
    extends ConsumerState<ArchitectureQueryScreen> {
  final controller = TextEditingController();
  List<ArchitecturePrediction> predictions = [];
  bool loading = false;
  String? error;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> predict() async {
    if (controller.text.trim().isEmpty) {
      setState(() => error = 'Enter a SQL query or HTTP request.');
      return;
    }
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final predictions = await ref
          .read(architectureServiceProvider)
          .predictAll(controller.text.trim());
      if (mounted) {
        setState(() {
          this.predictions = predictions;
        });
      }
    } catch (value) {
      if (mounted) setState(() => error = value.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Three-Architecture Query Comparison')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'New query prediction',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'One input is evaluated independently by normal profile, ratio ML, and the full flow.',
          ),
          const SizedBox(height: 20),
          TextField(
            controller: controller,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: 'SQL query / HTTP request',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loading ? null : predict,
            icon: const Icon(Icons.search),
            label: const Text('Compare predictions'),
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
          const SizedBox(height: 24),
          for (final prediction in predictions)
            _PredictionCard(prediction: prediction),
        ],
      ),
    );
  }
}

class _PredictionCard extends StatelessWidget {
  final ArchitecturePrediction prediction;
  const _PredictionCard({required this.prediction});

  @override
  Widget build(BuildContext context) {
    final malicious = prediction.label == 1;
    return Card(
      color: malicious ? Colors.red.shade50 : Colors.green.shade50,
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              prediction.architecture,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              malicious ? 'Malicious' : 'Benign',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: malicious ? Colors.red : Colors.green,
              ),
            ),
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: prediction.confidence,
              color: malicious ? Colors.red : Colors.green,
            ),
            const SizedBox(height: 8),
            Text(
              'Confidence ${_formatPercent(prediction.confidence)} | '
              'Benign ${_formatPercent(prediction.benignProbability)} | '
              'Malicious ${_formatPercent(prediction.maliciousProbability)}',
            ),
          ],
        ),
      ),
    );
  }

  String _formatPercent(double value) {
    return '${(value * 100).toStringAsFixed(1)}%';
  }
}
