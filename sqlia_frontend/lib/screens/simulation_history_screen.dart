import 'package:flutter/material.dart';

import '../models/architecture_performance.dart';

class SimulationHistoryScreen extends StatelessWidget {
  final List<List<ArchitecturePerformance>> history;
  const SimulationHistoryScreen({super.key, required this.history});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Architecture Comparison History')),
      body: history.isEmpty
          ? const Center(
              child: Text(
                'Run a performance comparison to see its results here.',
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.all(24),
              itemCount: history.length,
              itemBuilder: (context, index) {
                final run = history[index];
                return Card(
                  margin: const EdgeInsets.only(bottom: 16),
                  child: ExpansionTile(
                    title: Text('Comparison ${history.length - index}'),
                    subtitle: Text(
                      '${run.length} architecture paths evaluated',
                    ),
                    children: [
                      for (final result in run)
                        ListTile(
                          title: Text(result.displayName),
                          subtitle: Text(
                            'Accuracy: ${(result.accuracy * 100).toStringAsFixed(2)}% | '
                            'Precision: ${(result.precision * 100).toStringAsFixed(2)}% | '
                            'Recall: ${(result.recall * 100).toStringAsFixed(2)}% | '
                            'F1 score: ${(result.f1 * 100).toStringAsFixed(2)}%',
                          ),
                          trailing: Text('${result.durationMs} ms'),
                        ),
                    ],
                  ),
                );
              },
            ),
    );
  }
}
