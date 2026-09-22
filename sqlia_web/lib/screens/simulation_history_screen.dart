import 'package:flutter/material.dart';

import '../models/architecture_performance.dart';

class SimulationHistoryScreen extends StatelessWidget {
  final List<List<ArchitecturePerformance>> history;
  const SimulationHistoryScreen({super.key, required this.history});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Simulation History')),
      body: history.isEmpty
          ? const Center(child: Text('No simulation history yet.'))
          : ListView.builder(
              padding: const EdgeInsets.all(24),
              itemCount: history.length,
              itemBuilder: (context, index) {
                final run = history[index];
                return Card(
                  margin: const EdgeInsets.only(bottom: 16),
                  child: ExpansionTile(
                    title: Text('Simulation run ${history.length - index}'),
                    subtitle: Text('${run.length} architectures evaluated'),
                    children: [
                      for (final result in run)
                        ListTile(
                          title: Text(result.architecture),
                          subtitle: Text(
                            'Accuracy ${(result.accuracy * 100).toStringAsFixed(2)}% | '
                            'Precision ${(result.precision * 100).toStringAsFixed(2)}% | '
                            'Recall ${(result.recall * 100).toStringAsFixed(2)}% | '
                            'F1 ${(result.f1 * 100).toStringAsFixed(2)}%',
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
