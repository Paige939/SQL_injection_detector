import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';

class RankingEvaluationPanel extends StatelessWidget {
  final Map<String, dynamic> result;
  const RankingEvaluationPanel({super.key, required this.result});

  String _number(dynamic value) =>
      value == null ? 'Undefined' : (value as num).toStringAsFixed(4);

  @override
  Widget build(BuildContext context) {
    final metrics = Map<String, dynamic>.from(result['metrics'] as Map);
    if (!metrics.containsKey('rocAuc')) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: Text(
          'ROC-AUC / curves unavailable in this historical report. '
          'Open a ranking-derived report; missing values are not treated as zero.',
        ),
      );
    }
    final intervals = Map<String, dynamic>.from(
      result['confidenceIntervals'] as Map,
    );
    final curves = Map<String, dynamic>.from(result['rankingCurves'] as Map);
    final points = (curves['points'] as List)
        .map((value) => Map<String, dynamic>.from(value as Map))
        .toList();
    final operating = Map<String, dynamic>.from(
      curves['operatingPoint'] as Map,
    );
    final prevalence =
        ((metrics['tp'] as num) + (metrics['fn'] as num)) /
        (metrics['total'] as num);
    Widget metric(String key, String label) {
      final ci = Map<String, dynamic>.from(intervals[key] as Map);
      return Text(
        '$label: ${_number(metrics[key])} · '
        '95% CI ${_number(ci['lower'])}–${_number(ci['upper'])} · '
        'valid/undefined replicates ${ci['validReplicates']}/${ci['undefinedReplicates']}',
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Divider(height: 24),
        Text(
          'Ranking evaluation',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        metric('rocAuc', 'ROC-AUC'),
        metric('averagePrecision', 'Average precision (AP)'),
        const SizedBox(height: 8),
        const Text(
          'Orange dot: unchanged Validation-selected operating point. '
          'Grey dashed line: random-ranking ROC reference / PR class-prevalence reference. '
          'Curves sweep thresholds for evaluation only; no external threshold tuning.',
        ),
        SelectableText('Saved threshold: ${operating['threshold']}'),
        if (metrics['rocAuc'] == null)
          const Text('ROC curve undefined: both classes are required.')
        else
          _RankingChart(
            title: 'ROC curve',
            xLabel: 'False-positive rate',
            yLabel: 'True-positive rate (Recall)',
            points: points,
            operating: operating,
            xKey: 'fpr',
            yKey: 'recall',
            baseline: const [FlSpot(0, 0), FlSpot(1, 1)],
          ),
        if (metrics['averagePrecision'] == null)
          const Text('PR curve undefined: no positive samples.')
        else
          _RankingChart(
            title: 'Precision–Recall curve',
            xLabel: 'Recall',
            yLabel: 'Precision',
            points: points,
            operating: operating,
            xKey: 'recall',
            yKey: 'precision',
            baseline: [FlSpot(0, prevalence), FlSpot(1, prevalence)],
          ),
        const Text(
          'AP is not trapezoidal PR-AUC. Ranking scores do not establish '
          'probability calibration or performance on independent new sources.',
        ),
        Text(
          curves['displayPolicy'] as String,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }
}

class _RankingChart extends StatelessWidget {
  final String title, xLabel, yLabel, xKey, yKey;
  final List<Map<String, dynamic>> points;
  final Map<String, dynamic> operating;
  final List<FlSpot>? baseline;
  const _RankingChart({
    required this.title,
    required this.xLabel,
    required this.yLabel,
    required this.points,
    required this.operating,
    required this.xKey,
    required this.yKey,
    this.baseline,
  });

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$title · Y: $yLabel · X: $xLabel'),
        const SizedBox(height: 12),
        SizedBox(
          height: 240,
          child: LineChart(
            duration: Duration.zero,
            LineChartData(
              minX: 0,
              maxX: 1,
              minY: 0,
              maxY: 1,
              lineTouchData: const LineTouchData(enabled: false),
              gridData: const FlGridData(
                horizontalInterval: .25,
                verticalInterval: .25,
              ),
              titlesData: const FlTitlesData(
                topTitles: AxisTitles(
                  sideTitles: SideTitles(showTitles: false),
                ),
                rightTitles: AxisTitles(
                  sideTitles: SideTitles(showTitles: false),
                ),
                bottomTitles: AxisTitles(
                  sideTitles: SideTitles(showTitles: true, interval: .5),
                ),
                leftTitles: AxisTitles(
                  sideTitles: SideTitles(
                    showTitles: true,
                    interval: .5,
                    reservedSize: 36,
                  ),
                ),
              ),
              lineBarsData: [
                if (baseline != null)
                  LineChartBarData(
                    spots: baseline!,
                    color: Colors.grey,
                    dashArray: [5, 5],
                    barWidth: 1,
                    dotData: const FlDotData(show: false),
                  ),
                LineChartBarData(
                  spots: [
                    for (final point in points)
                      FlSpot(
                        (point[xKey] as num).toDouble(),
                        (point[yKey] as num).toDouble(),
                      ),
                  ],
                  color: Colors.teal,
                  barWidth: 2,
                  dotData: const FlDotData(show: false),
                ),
                LineChartBarData(
                  spots: [
                    FlSpot(
                      (operating[xKey] as num).toDouble(),
                      (operating[yKey] as num).toDouble(),
                    ),
                  ],
                  color: Colors.deepOrange,
                  dotData: FlDotData(
                    getDotPainter: (_, _, _, _) => FlDotCirclePainter(
                      radius: 5,
                      color: Colors.deepOrange,
                      strokeWidth: 1,
                      strokeColor: Colors.white,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}
