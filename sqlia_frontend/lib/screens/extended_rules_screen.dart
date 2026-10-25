import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/detection_provider.dart';

enum ExtendedSection { rules, evaluation }

class ExtendedRulesScreen extends ConsumerWidget {
  final ExtendedSection section;
  final bool embedded;
  const ExtendedRulesScreen({
    super.key,
    this.section = ExtendedSection.rules,
    this.embedded = true,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rules = ref.watch(extendedRulesProvider);
    final ablation = ref.watch(extendedAblationProvider);
    final statistics = ref.watch(extendedStatisticsProvider);
    final evaluationChildren = <Widget>[
      Text(
        'Extended ARM rule-ablation results on the held-out test set',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      const SizedBox(height: 8),
      ablation.when(
        loading: () => const LinearProgressIndicator(),
        error: (e, _) => Text('Ablation results unavailable: $e'),
        data: (all) {
          final rows = all.where((r) => r['split'] == 'test').toList();
          return SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: DataTable(
              columns: const [
                DataColumn(label: Text('Configuration')),
                DataColumn(label: Text('Classifier')),
                DataColumn(label: Text('Rule count')),
                DataColumn(label: Text('Precision')),
                DataColumn(label: Text('Recall')),
                DataColumn(label: Text('F1 score')),
                DataColumn(label: Text('False positive rate')),
              ],
              rows: [
                for (final r in rows)
                  DataRow(
                    cells: [
                      DataCell(Text('${r['config']}')),
                      DataCell(Text('${r['classifier']}')),
                      DataCell(Text('${r['rule_count']}')),
                      DataCell(Text('${r['precision']}')),
                      DataCell(Text('${r['recall']}')),
                      DataCell(Text('${r['f1']}')),
                      DataCell(Text('${r['fpr']}')),
                    ],
                  ),
              ],
            ),
          );
        },
      ),
      const SizedBox(height: 16),
      ablation.maybeWhen(
        data: (rows) => _AblationChart(rows: rows),
        orElse: () => const SizedBox.shrink(),
      ),
      const SizedBox(height: 24),
      Text(
        'Statistical validation (Random Forest)',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      const SizedBox(height: 8),
      const Text(
        'This offline ablation isolates the contribution of Extended ARM rule '
        'types. Cross-validation runs on the train split only (rules re-mined '
        'per fold); McNemar and bootstrap use the untouched test split. The '
        'live comparison of the two exhibition methods is on the Performance screen.',
      ),
      const SizedBox(height: 8),
      statistics.when(
        loading: () => const LinearProgressIndicator(),
        error: (e, _) => Text('Statistics unavailable: $e'),
        data: (rows) => _StatisticsTables(rows: rows),
      ),
    ];
    final ruleChildren = <Widget>[
      Text(
        'Association rule quality: support vs. confidence',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      const SizedBox(height: 8),
      rules.maybeWhen(
        data: (items) => _RuleScatter(rules: items),
        orElse: () => const SizedBox.shrink(),
      ),
      const SizedBox(height: 24),
      Text('Mined rules', style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 8),
      rules.when(
        loading: () => const LinearProgressIndicator(),
        error: (e, _) => Text('Rules unavailable: $e'),
        data: (items) => Column(
          children: [
            for (final r in items)
              Card(
                child: ListTile(
                  leading: Chip(label: Text('${r['type']}')),
                  title: Text('${r['description']}'),
                ),
              ),
          ],
        ),
      ),
    ];
    final body = ListView(
      padding: const EdgeInsets.all(24),
      children: section == ExtendedSection.rules
          ? ruleChildren
          : evaluationChildren,
    );
    if (embedded) return body;
    return Scaffold(
      appBar: AppBar(title: const Text('Extended Association Rule Analysis')),
      body: body,
    );
  }
}

const _typeColors = {
  'itemset': Colors.blue,
  'negative': Colors.red,
  'sequential': Colors.green,
};

class _RuleScatter extends StatelessWidget {
  final List<Map<String, dynamic>> rules;
  const _RuleScatter({required this.rules});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        SizedBox(
          height: 300,
          child: ScatterChart(
            ScatterChartData(
              minX: 0,
              maxX: 1,
              minY: 0,
              maxY: 1.05,
              gridData: const FlGridData(show: true),
              titlesData: const FlTitlesData(
                topTitles: AxisTitles(),
                rightTitles: AxisTitles(),
                bottomTitles: AxisTitles(
                  axisNameWidget: Text('Support'),
                  sideTitles: SideTitles(showTitles: true, reservedSize: 28),
                ),
                leftTitles: AxisTitles(
                  axisNameWidget: Text('Confidence'),
                  sideTitles: SideTitles(showTitles: true, reservedSize: 36),
                ),
              ),
              scatterSpots: [
                for (final r in rules)
                  ScatterSpot(
                    (r['support'] as num).toDouble(),
                    (r['confidence'] as num).toDouble(),
                    dotPainter: FlDotCirclePainter(
                      radius: 3 + ((r['lift'] as num).toDouble() * 2),
                      color: (_typeColors[r['type']] ?? Colors.grey).withValues(
                        alpha: 0.6,
                      ),
                    ),
                  ),
              ],
              scatterTouchData: ScatterTouchData(
                touchTooltipData: ScatterTouchTooltipData(
                  getTooltipItems: (spot) {
                    final i = rules.indexWhere(
                      (r) =>
                          (r['support'] as num).toDouble() == spot.x &&
                          (r['confidence'] as num).toDouble() == spot.y,
                    );
                    return ScatterTooltipItem(
                      i < 0 ? '' : '${rules[i]['description']}',
                      textStyle: const TextStyle(color: Colors.white),
                    );
                  },
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 16,
          children: [
            for (final e in _typeColors.entries)
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.circle, size: 12, color: e.value),
                  const SizedBox(width: 4),
                  Text(switch (e.key) {
                    'itemset' => 'Frequent itemset',
                    'negative' => 'Negative rule',
                    'sequential' => 'Sequential rule',
                    _ => e.key,
                  }),
                ],
              ),
            const Text('Dot size indicates lift'),
          ],
        ),
      ],
    );
  }
}

class _AblationChart extends StatelessWidget {
  final List<Map<String, dynamic>> rows;
  const _AblationChart({required this.rows});

  @override
  Widget build(BuildContext context) {
    final data = rows
        .where((r) => r['classifier'] == 'RandomForest' && r['split'] == 'test')
        .toList();
    if (data.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'False positive rate by configuration (Random Forest; lower is better)',
        ),
        const SizedBox(height: 8),
        SizedBox(
          height: 260,
          child: BarChart(
            BarChartData(
              barGroups: [
                for (var i = 0; i < data.length; i++)
                  BarChartGroupData(
                    x: i,
                    barRods: [
                      BarChartRodData(
                        toY: double.tryParse('${data[i]['fpr']}') ?? 0,
                        width: 22,
                        color: data[i]['config'] == 'EXTENDED'
                            ? Colors.red
                            : Colors.blueGrey,
                      ),
                    ],
                  ),
              ],
              titlesData: FlTitlesData(
                topTitles: const AxisTitles(),
                rightTitles: const AxisTitles(),
                leftTitles: const AxisTitles(
                  sideTitles: SideTitles(showTitles: true, reservedSize: 44),
                ),
                bottomTitles: AxisTitles(
                  sideTitles: SideTitles(
                    showTitles: true,
                    reservedSize: 48,
                    getTitlesWidget: (v, _) => Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text(
                        '${data[v.toInt()]['config']}',
                        style: const TextStyle(fontSize: 9),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

String _p(String raw) {
  final v = double.tryParse(raw);
  if (v == null) return '';
  return v < 0.001 ? '<0.001' : v.toStringAsFixed(3);
}

class _StatisticsTables extends StatelessWidget {
  final List<Map<String, dynamic>> rows;
  const _StatisticsTables({required this.rows});

  @override
  Widget build(BuildContext context) {
    final cv = rows.where((r) => r['analysis'] == 'cv').toList();
    final tests = rows.where((r) => r['analysis'] != 'cv').toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Repeated 5-fold cross-validation (5 folds × 2 repeats)'),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            columns: const [
              DataColumn(label: Text('Configuration')),
              DataColumn(label: Text('Metric')),
              DataColumn(label: Text('Mean ± standard deviation')),
            ],
            rows: [
              for (final r in cv)
                DataRow(
                  cells: [
                    DataCell(Text('${r['comparison']}')),
                    DataCell(
                      Text('${r['metric']}'.replaceAll('_mean_std', '')),
                    ),
                    DataCell(Text('${r['value_a']} ± ${r['value_b']}')),
                  ],
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        const Text('Significance tests: extended model vs. baseline'),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            columns: const [
              DataColumn(label: Text('Statistical test')),
              DataColumn(label: Text('Comparison')),
              DataColumn(label: Text('Metric')),
              DataColumn(label: Text('Baseline')),
              DataColumn(label: Text('Extended model')),
              DataColumn(label: Text('95% confidence interval of difference')),
              DataColumn(label: Text('P-value')),
            ],
            rows: [
              for (final r in tests)
                DataRow(
                  cells: [
                    DataCell(Text('${r['analysis']}')),
                    DataCell(Text('${r['comparison']}')),
                    DataCell(Text('${r['metric']}')),
                    DataCell(Text('${r['value_a']}')),
                    DataCell(Text('${r['value_b']}')),
                    DataCell(
                      Text(
                        '${r['ci_low']}'.isEmpty
                            ? ''
                            : '[${r['ci_low']}, ${r['ci_high']}]',
                      ),
                    ),
                    DataCell(Text(_p('${r['p_value']}'))),
                  ],
                ),
            ],
          ),
        ),
        const Text(
          'For McNemar results, the baseline and extended-model values count test queries '
          'misclassified only by that model.',
          style: TextStyle(fontSize: 12),
        ),
      ],
    );
  }
}
