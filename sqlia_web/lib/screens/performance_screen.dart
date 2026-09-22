import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:fl_chart/fl_chart.dart';

import '../models/architecture_performance.dart';
import '../providers/detection_provider.dart';
import 'simulation_history_screen.dart';

class PerformanceScreen extends ConsumerStatefulWidget {
  const PerformanceScreen({super.key});

  @override
  ConsumerState<PerformanceScreen> createState() => _PerformanceScreenState();
}

class _PerformanceScreenState extends ConsumerState<PerformanceScreen> {
  final sampleController = TextEditingController(text: '200');
  String dataset = 'data/processed/combined_preprocessed.csv';
  List<ArchitecturePerformance> results = [];
  final List<List<ArchitecturePerformance>> history = [];
  bool loading = false;
  String? error;

  @override
  void dispose() {
    sampleController.dispose();
    super.dispose();
  }

  Future<void> compare() async {
    final sampleSize = int.tryParse(sampleController.text);
    if (sampleSize == null || sampleSize <= 0) {
      setState(() => error = 'Sample size must be a positive integer.');
      return;
    }
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final value = await ref
          .read(architectureServiceProvider)
          .compare(datasetPath: dataset, sampleSize: sampleSize);
      if (mounted) {
        setState(() {
          results = value;
          history.insert(0, value);
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
      appBar: AppBar(title: const Text('Architecture Performance')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Compare three architectures',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'Run the same held-out dataset and sample size for a fair comparison.',
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<String>(
            initialValue: dataset,
            decoration: const InputDecoration(
              labelText: 'Held-out dataset',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(
                value: 'data/processed/combined_preprocessed.csv',
                child: Text('Combined preprocessed dataset (3 CSVs)'),
              ),
            ],
            onChanged: loading
                ? null
                : (value) => setState(() => dataset = value!),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: sampleController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Sample size',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loading ? null : compare,
            icon: const Icon(Icons.compare_arrows),
            label: const Text('Run comparison'),
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
          if (results.isNotEmpty) ...[
            SizedBox(
              height: 320,
              child: BarChart(
                BarChartData(
                  maxY: 1,
                  barGroups: [
                    for (var index = 0; index < results.length; index++)
                      BarChartGroupData(
                        x: index,
                        barRods: [
                          BarChartRodData(
                            toY: results[index].accuracy,
                            color: Colors.blue,
                            width: 10,
                          ),
                          BarChartRodData(
                            toY: results[index].precision,
                            color: Colors.green,
                            width: 10,
                          ),
                          BarChartRodData(
                            toY: results[index].recall,
                            color: Colors.orange,
                            width: 10,
                          ),
                          BarChartRodData(
                            toY: results[index].f1,
                            color: Colors.red,
                            width: 10,
                          ),
                        ],
                      ),
                  ],
                ),
              ),
            ),
            const Text(
              'Blue Accuracy | Green Precision | Orange Recall | Red F1',
            ),
            const SizedBox(height: 16),
          ],
          for (final result in results) _PerformanceCard(result: result),
          const SizedBox(height: 24),
          OutlinedButton.icon(
            icon: const Icon(Icons.history),
            label: const Text('Open complete history'),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => SimulationHistoryScreen(history: history),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PerformanceCard extends StatelessWidget {
  final ArchitecturePerformance result;
  const _PerformanceCard({required this.result});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            result.architecture,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 16,
            runSpacing: 8,
            children: [
              Text('Accuracy ${(result.accuracy * 100).toStringAsFixed(1)}%'),
              Text('Precision ${(result.precision * 100).toStringAsFixed(1)}%'),
              Text('Recall ${(result.recall * 100).toStringAsFixed(1)}%'),
              Text('F1 ${(result.f1 * 100).toStringAsFixed(1)}%'),
              Text('${result.durationMs} ms'),
            ],
          ),
        ],
      ),
    ),
  );
}
