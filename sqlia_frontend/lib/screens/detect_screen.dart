import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/detection_provider.dart';
import '../core/detection_input.dart';
import '../models/detect_result.dart';

// Detects one input value without requiring a complete SQL statement.
class DetectScreen extends ConsumerStatefulWidget {
  // Creates an instance of [DetectScreen].
  const DetectScreen({super.key});
  // Returns the state object for this widget.
  @override
  ConsumerState<DetectScreen> createState() => _DetectScreenState();
}

// The state class for [DetectScreen] that manages the input and detection result.
class _DetectScreenState extends ConsumerState<DetectScreen> {
  final controller = TextEditingController();
  String? submittedSql;
  String? inputError;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final resultAsync = submittedSql == null
        ? null
        : ref.watch(detectResultProvider(submittedSql!));
    return Scaffold(
      appBar: AppBar(title: const Text('Single Input SQL Injection Detection')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            controller: controller,
            maxLines: 4,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: detectionInputLabel,
              hintText: detectionInputHint,
            ),
          ), //TextField
          const SizedBox(height: 12),
          ElevatedButton(
            onPressed: () => setState(() {
              inputError = validateDetectionInput(controller.text);
              submittedSql = inputError == null ? controller.text : null;
            }),
            child: const Text('Check for SQL injection'),
          ), //ElevatedButton
          if (inputError != null)
            Text(inputError!, style: const TextStyle(color: Colors.red)),
          const SizedBox(height: 8),
          const Text(detectionDisclaimer),
          const Text('Uses the current website model, including verified online feedback.'),
          const SizedBox(height: 24),
          if (resultAsync != null)
            resultAsync.when(
              data: (result) => _ResultCard(result: result),
              loading: () => const CircularProgressIndicator(),
              error: (e, _) =>
                  Text('Error: $e', style: const TextStyle(color: Colors.red)),
            ),
        ],
      ),
    );
  }
}

// A widget that displays the detection result in a card format.
class _ResultCard extends StatelessWidget {
  final DetectResult result;
  const _ResultCard({required this.result});
  @override
  Widget build(BuildContext context) {
    final isMalicious = result.label == 1;
    return Card(
      color: isMalicious ? Colors.red.shade50 : Colors.green.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              isMalicious ? 'Suspected SQL injection' : 'Input appears benign',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 18,
                color: isMalicious ? Colors.red : Colors.green,
              ),
            ),
            Text(
              'Class score: ${(result.confidence * 100).toStringAsFixed(1)}% (not a calibrated probability)',
            ),
            if (result.threshold != null)
              Text(
                'SQLi score: ${result.score} | Decision threshold: ${result.threshold}',
              ),
            if (result.modelMode != null) Text('Model: ${result.modelMode}'),
            if (result.checkpointSha256 != null)
              SelectableText('Checkpoint SHA-256: ${result.checkpointSha256}'),
            const SizedBox(height: 8),
            const Text(
              'Extracted detection features:',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            ...result.features.entries.map<Widget>(
              (e) => Text('${e.key}: ${e.value}'),
            ),
          ],
        ),
      ),
    );
  }
}
