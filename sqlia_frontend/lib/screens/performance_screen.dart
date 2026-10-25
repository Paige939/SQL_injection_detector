import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:fl_chart/fl_chart.dart';

import '../models/architecture_performance.dart';
import '../models/architecture_names.dart';
import '../providers/detection_provider.dart';
import 'simulation_history_screen.dart';

class PerformanceScreen extends ConsumerStatefulWidget {
  final bool extendedOnly;
  const PerformanceScreen({super.key, this.extendedOnly = false});

  @override
  ConsumerState<PerformanceScreen> createState() => _PerformanceScreenState();
}

class _PerformanceScreenState extends ConsumerState<PerformanceScreen> {
  final sampleController = TextEditingController(text: '5345');
  String dataset = 'data/processed/test.csv';
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
      results = [];
    });
    try {
      final value = await ref
          .read(architectureServiceProvider)
          .compare(
            datasetPath: dataset,
            sampleSize: sampleSize,
            extendedOnly: widget.extendedOnly,
          );
      if (mounted) {
        setState(() {
          results = value;
          history.insert(0, value);
        });
      }
    } on DioException catch (exception) {
      final responseData = exception.response?.data;
      final message = responseData is Map
          ? responseData['error']?.toString()
          : null;
      if (mounted) {
        setState(
          () => error = message ?? exception.message ?? 'Request failed.',
        );
      }
    } catch (exception) {
      if (mounted) setState(() => error = exception.toString());
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
              ? 'Extended ARM Performance Comparison'
              : 'All Architecture Performance Comparison',
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            widget.extendedOnly
                ? 'Compare the two Extended ARM methods'
                : 'Compare all five detection architectures',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          Text(
            widget.extendedOnly
                ? 'Compare both Extended ARM methods using the current website models on the same test records without updates.'
                : 'Compare all five architectures using the current website models on the same test records without updates.',
          ),
          const SizedBox(height: 8),
          const Text(
            'Results reflect the current interactive models, including prior feedback. '
            'This is a website benchmark, not the independent frozen research experiment.',
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<String>(
            initialValue: dataset,
            decoration: const InputDecoration(
              labelText: 'Evaluation dataset',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(
                value: 'data/processed/test.csv',
                child: Text('Test dataset'),
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
              labelText: 'Number of test records',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: loading ? null : compare,
            icon: const Icon(Icons.compare_arrows),
            label: const Text('Compare performance'),
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
                  titlesData: FlTitlesData(
                    topTitles: const AxisTitles(
                      sideTitles: SideTitles(showTitles: false),
                    ),
                    rightTitles: const AxisTitles(
                      sideTitles: SideTitles(showTitles: false),
                    ),
                    bottomTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        interval: 1,
                        reservedSize: 42,
                        getTitlesWidget: (value, meta) {
                          final index = value.toInt();
                          if (value != index ||
                              index < 0 ||
                              index >= results.length) {
                            return const SizedBox.shrink();
                          }
                          final result = results[index];
                          return SideTitleWidget(
                            meta: meta,
                            child: Tooltip(
                              message: result.displayName,
                              child: Text(
                                architectureShortName(result.architecture),
                                style: const TextStyle(fontSize: 12),
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ),
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
              'Chart legend — Blue: Accuracy | Green: Precision | Orange: Recall | Red: F1 score',
            ),
            const SizedBox(height: 16),
          ],
          for (final result in results) _PerformanceCard(result: result),
          const SizedBox(height: 24),
          OutlinedButton.icon(
            icon: const Icon(Icons.history),
            label: const Text('View comparison history'),
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
            result.displayName,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 16,
            runSpacing: 8,
            children: [
              Text('Accuracy: ${(result.accuracy * 100).toStringAsFixed(1)}%'),
              Text(
                'Precision: ${(result.precision * 100).toStringAsFixed(1)}%',
              ),
              Text('Recall: ${(result.recall * 100).toStringAsFixed(1)}%'),
              Text('F1 score: ${(result.f1 * 100).toStringAsFixed(1)}%'),
              Text(
                'False positive rate: ${(result.fpr * 100).toStringAsFixed(1)}%',
              ),
              Text(
                'TP ${result.tp} | FP ${result.fp} | TN ${result.tn} | FN ${result.fn}',
              ),
              Text('Evaluated test rows: ${result.total}'),
              Text('Training updates: ${result.trainingSamples}'),
              Text(
                'Decision threshold: ${result.threshold.toStringAsPrecision(6)}',
              ),
              if (result.validationF1 != null)
                Text(
                  'Validation F1: ${(result.validationF1! * 100).toStringAsFixed(1)}%',
                ),
              Text('Evaluation time: ${result.durationMs} ms'),
            ],
          ),
          const SizedBox(height: 8),
          Text('Mode: ${result.evaluationMode}'),
          if (result.datasetFingerprint != null)
            SelectableText('Dataset SHA-256: ${result.datasetFingerprint}'),
          if (result.testFingerprint != null)
            SelectableText('Evaluated test SHA-256: ${result.testFingerprint}'),
          if (result.checkpointSha256 != null)
            SelectableText('Checkpoint SHA-256: ${result.checkpointSha256}'),
          _groupMetrics('Source combinations', result.bySource),
          _groupMetrics('Input types', result.byInputType),
        ],
      ),
    ),
  );

  Widget _groupMetrics(
    String title,
    Map<String, dynamic> groups,
  ) => ExpansionTile(
    title: Text(title),
    children: [
      for (final entry in groups.entries)
        ListTile(
          title: Text(entry.key),
          subtitle: Text(
            'Rows: ${entry.value['total']} | '
            'F1: ${((entry.value['f1'] as num) * 100).toStringAsFixed(1)}% | '
            'FPR: ${((entry.value['fpr'] as num) * 100).toStringAsFixed(1)}%',
          ),
        ),
    ],
  );
}
