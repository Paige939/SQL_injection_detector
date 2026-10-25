import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/architecture_prediction.dart';
import '../providers/detection_provider.dart';
import '../core/detection_input.dart';

class ArchitectureQueryScreen extends ConsumerStatefulWidget {
  final bool extendedOnly;
  const ArchitectureQueryScreen({super.key, this.extendedOnly = false});

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
    final inputError = validateDetectionInput(controller.text);
    if (inputError != null) {
      setState(() {
        error = inputError;
        predictions = [];
      });
      return;
    }
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final predictions = await ref
          .read(architectureServiceProvider)
          .predictAll(controller.text, extendedOnly: widget.extendedOnly);
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
      appBar: AppBar(
        title: Text(
          widget.extendedOnly
              ? 'Extended ARM Method Comparison'
              : 'All Architecture Comparison',
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Compare predictions for one input',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          Text(
            widget.extendedOnly
                ? 'Compare Pure Extended ARM with Extended ARM + Incremental Learning. Unmatched rule-only inputs are marked for review because no matching rule is not proof of safety.'
                : 'Compare all five detection architectures on the same input. The Extended ARM methods include rule-only and incremental-learning results.',
          ),
          const SizedBox(height: 8),
          const Text(detectionDisclaimer),
          const Text(
            'Uses the current website models. Verified feedback and replay updates may change later predictions.',
          ),
          const SizedBox(height: 20),
          TextField(
            controller: controller,
            maxLines: 5,
            decoration: const InputDecoration(
              labelText: detectionInputLabel,
              hintText: detectionInputHint,
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loading ? null : predict,
            icon: const Icon(Icons.search),
            label: Text(
              widget.extendedOnly
                  ? 'Compare both Extended ARM methods'
                  : 'Compare all five architectures',
            ),
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
    final pureRuleOnly = prediction.architecture == 'PURE_EXTENDED_ARM';
    final needsReview = pureRuleOnly && prediction.action == 'review';
    final resultColor = needsReview
        ? Colors.orange
        : malicious
        ? Colors.red
        : Colors.green;
    return Card(
      color: needsReview
          ? Colors.orange.shade50
          : malicious
          ? Colors.red.shade50
          : Colors.green.shade50,
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              prediction.displayName,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              pureRuleOnly
                  ? malicious
                        ? 'Extended ARM rule matched'
                        : 'Below decision threshold — manual review needed'
                  : malicious
                  ? 'Suspected SQL injection'
                  : 'Input appears benign',
              style: TextStyle(fontWeight: FontWeight.bold, color: resultColor),
            ),
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: pureRuleOnly
                  ? prediction.maliciousProbability
                  : prediction.confidence,
              color: resultColor,
            ),
            const SizedBox(height: 8),
            if (pureRuleOnly)
              Text(
                'Maximum matched-rule confidence: '
                '${_formatPercent(prediction.maliciousProbability)}',
              )
            else
              Text(
                'Class score: ${_formatPercent(prediction.confidence)}\n'
                'Benign score: ${_formatPercent(prediction.benignProbability)} | '
                'SQL injection score: ${_formatPercent(prediction.maliciousProbability)}\n'
                'Scores are not calibrated probabilities.',
              ),
            if (prediction.threshold != null)
              Text('Decision threshold: ${prediction.threshold}'),
            if (prediction.modelMode != null)
              Text('Model: ${prediction.modelMode}'),
            if (prediction.checkpointSha256 != null)
              SelectableText(
                'Checkpoint SHA-256: ${prediction.checkpointSha256}',
              ),
            if (prediction.action != null) ...[
              const SizedBox(height: 8),
              Chip(
                label: Text(
                  'Recommended action: ${prediction.action!.toUpperCase()}',
                ),
                backgroundColor: switch (prediction.action) {
                  'block' => Colors.red.shade200,
                  'review' => Colors.orange.shade200,
                  _ => Colors.green.shade200,
                },
              ),
            ],
            if (prediction.matchedRules.isNotEmpty) ...[
              const SizedBox(height: 8),
              const Text(
                'Matched extended association rules:',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              for (final rule in prediction.matchedRules) Text('• $rule'),
            ],
          ],
        ),
      ),
    );
  }

  String _formatPercent(double value) {
    return '${(value * 100).toStringAsFixed(1)}%';
  }
}
