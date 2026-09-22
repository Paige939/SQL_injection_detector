import 'dart:async';

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/accuracy_point.dart';
import '../providers/detection_provider.dart';
// A screen that monitors the status and accuracy of the incremental learning process.
class MonitorScreen extends ConsumerStatefulWidget {
  const MonitorScreen({super.key});

  @override
  ConsumerState<MonitorScreen> createState() => _MonitorScreenState();
}
// The state class for [MonitorScreen] that manages the status, accuracy points, loading state, and error messages related to the incremental learning process.
class _MonitorScreenState extends ConsumerState<MonitorScreen> {
  Timer? timer;
  bool isLoading = true;
  String? errorMessage;
  Map<String, dynamic>? status;
  List<AccuracyPoint> points = [];

  @override
  // Initialize the state of the widget and set up a periodic timer to refresh the status and accuracy history of the incremental learning process every 3 seconds.
  void initState() {
    super.initState();
    refresh();
    // Set up a periodic timer that calls the refresh method every 3 seconds to update the status and accuracy history of the incremental learning process.
    timer = Timer.periodic(
      const Duration(seconds: 3),
      (_) => refresh(),
    );
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }
  // Refreshes the status and accuracy history of the incremental learning process by fetching data from the API using the [IncrementalService] provided by Riverpod. It updates the state accordingly, handling loading state, error messages, and the list of accuracy points.
  Future<void> refresh() async {
    try {
      final service = ref.read(incrementalServiceProvider);

      final values = await Future.wait([
        service.getStatus(),
        service.getAccuracyHistory(),
      ]);

      if (!mounted) {
        return;
      }

      setState(() {
        status = values[0] as Map<String, dynamic>;
        points = values[1] as List<AccuracyPoint>;
        errorMessage = null;
        isLoading = false;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }

      setState(() {
        errorMessage = error.toString();
        isLoading = false;
      });
    }
  }

  @override
  // Builds the UI for the monitor screen, displaying the model status, total trained, evaluation points, and a line chart of prequential accuracy.
  Widget build(BuildContext context) {
    final isWarmUp = status?['isWarmUp'] == true;
    final totalTrained = status?['totalTrained'] ?? 0;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Incremental Learning Monitor'),
        actions: [
          IconButton(
            onPressed: refresh,
            tooltip: 'Refresh',
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: isLoading
            ? const Center(child: CircularProgressIndicator())
            : errorMessage != null
                ? Center(child: Text(errorMessage!))
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Wrap(
                        spacing: 12,
                        runSpacing: 12,
                        children: [
                          _StatusCard(
                            label: 'Model status',
                            value: isWarmUp ? 'Ready' : 'Not initialized',
                          ),
                          _StatusCard(
                            label: 'Total trained',
                            value: '$totalTrained',
                          ),
                          _StatusCard(
                            label: 'Evaluation points',
                            value: '${points.length}',
                          ),
                        ],
                      ),
                      const SizedBox(height: 28),
                      Text(
                        'Prequential Accuracy',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 12),
                      Expanded(
                        child: points.isEmpty
                            ? const Center(
                                child: Text(
                                  'Run an attack simulation to generate accuracy data.',
                                ),
                              )
                            : LineChart(
                                LineChartData(
                                  minY: 0,
                                  maxY: 100,
                                  gridData: const FlGridData(show: true),
                                  borderData: FlBorderData(show: true),
                                  titlesData: FlTitlesData(
                                    topTitles: const AxisTitles(
                                      sideTitles: SideTitles(showTitles: false),
                                    ),
                                    rightTitles: const AxisTitles(
                                      sideTitles: SideTitles(showTitles: false),
                                    ),
                                    bottomTitles: AxisTitles(
                                      axisNameWidget: const Text(
                                        'Evaluation checkpoint',
                                      ),
                                      sideTitles: SideTitles(
                                        showTitles: true,
                                        interval: 1,
                                        getTitlesWidget: (value, meta) {
                                          return Text(
                                            value.toInt().toString(),
                                            style: const TextStyle(
                                              color: Colors.white,
                                            ),
                                          );
                                        },
                                      ),
                                    ),
                                    leftTitles: AxisTitles(
                                      axisNameWidget: const Text('Accuracy (%)'),
                                      sideTitles: SideTitles(
                                        showTitles: true,
                                        reservedSize: 42,
                                        getTitlesWidget: (value, meta) {
                                          return Text(
                                            value.toInt().toString(),
                                            style: const TextStyle(
                                              color: Colors.white,
                                            ),
                                          );
                                        },
                                      ),
                                    ),
                                  ),
                                  lineBarsData: [
                                    LineChartBarData(
                                      isCurved: true,
                                      color: Theme.of(context)
                                          .colorScheme
                                          .primary,
                                      barWidth: 3,
                                      dotData: const FlDotData(show: true),
                                      spots: points
                                          .map(
                                            (point) => FlSpot(
                                              point.index.toDouble(),
                                              point.accuracy * 100,
                                            ),
                                          )
                                          .toList(),
                                    ),
                                  ],
                                ),
                              ),
                      ),
                    ],
                  ),
      ),
    );
  }
}
// A widget that displays a label and its corresponding value in a card format.
class _StatusCard extends StatelessWidget {
  final String label;
  final String value;

  const _StatusCard({
    required this.label,
    required this.value,
  });

  @override
  // Builds a card widget that displays a label and its corresponding value, styled according to the current theme.
  Widget build(BuildContext context) {
    return SizedBox(
      width: 180,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label),
              const SizedBox(height: 6),
              Text(
                value,
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ],
          ),
        ),
      ),
    );
  }
}