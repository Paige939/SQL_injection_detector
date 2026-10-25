import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/api_client.dart';
import '../models/external_report.dart';
import '../models/rule_ablation_report.dart';
import '../services/external_report_download.dart';
import '../services/rule_ablation_report_service.dart';
import '../widgets/ranking_evaluation_panel.dart';

const _researchNotice =
    'Development experiment — not an independent blind test.\n'
    'Research only — not deployed. Website detection remains unchanged.';

const _metricLabels = {
  'accuracy': 'Accuracy',
  'precision': 'Precision',
  'recall': 'Recall',
  'f1': 'F1',
  'fpr': 'False-positive rate (FPR)',
  'averagePrecision': 'Average precision (AP)',
  'rocAuc': 'ROC-AUC',
};

String _policy(String value) => switch (value) {
  'BASELINE' => 'Baseline',
  'NO_NEGATIVE' => 'No negative rules',
  'SQL_CONTEXT' => 'SQL context',
  _ => throw FormatException('Unsupported rule policy: $value'),
};
String _percent(dynamic value) {
  if (value == null) return 'Undefined';
  final percent = (value as num) * 100;
  if (percent > 0 && percent < .01) {
    return '${percent.toStringAsPrecision(3)}%';
  }
  if (percent < 100 && percent > 99.99) {
    return '${percent.toStringAsFixed(4)}%';
  }
  return '${percent.toStringAsFixed(2)}%';
}

class RuleAblationReportList extends StatefulWidget {
  final RuleAblationReportsRepository? repository;
  const RuleAblationReportList({super.key, this.repository});
  @override
  State<RuleAblationReportList> createState() => _RuleAblationReportListState();
}

class _RuleAblationReportListState extends State<RuleAblationReportList> {
  late final _repository =
      widget.repository ?? RuleAblationReportService(ApiClient());
  late Future<List<RuleAblationReport>> _reports = _repository.listReports();
  void _reload() => setState(() {
    _reports = _repository.listReports();
    // FutureBuilder observes the original error on the next frame.
    _reports.ignore();
  });

  @override
  Widget build(BuildContext context) => FutureBuilder<List<RuleAblationReport>>(
    future: _reports,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done) {
        return const Center(child: CircularProgressIndicator());
      }
      if (snapshot.hasError) {
        return _ErrorPanel(error: snapshot.error!, retry: _reload);
      }
      return ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const _Notice(),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: _reload,
              icon: const Icon(Icons.refresh),
              label: const Text('Refresh experiments'),
            ),
          ),
          if (snapshot.data!.isEmpty)
            const Text(
              'No saved rule ablation experiments yet. No training runs here.',
            ),
          for (final report in snapshot.data!)
            Card(
              child: ListTile(
                title: Text(report.id),
                subtitle: Text(
                  '${report.status} · Saved development experiment',
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => RuleAblationReportScreen(
                      reportId: report.id,
                      repository: _repository,
                    ),
                  ),
                ),
              ),
            ),
        ],
      );
    },
  );
}

class RuleAblationReportScreen extends StatefulWidget {
  final String reportId;
  final RuleAblationReportsRepository? repository;
  const RuleAblationReportScreen({
    super.key,
    required this.reportId,
    this.repository,
  });
  @override
  State<RuleAblationReportScreen> createState() =>
      _RuleAblationReportScreenState();
}

class _RuleAblationReportScreenState extends State<RuleAblationReportScreen> {
  late final _repository =
      widget.repository ?? RuleAblationReportService(ApiClient());
  late Future<RuleAblationReport> _report = _repository.getReport(
    widget.reportId,
  );
  String _architecture = 'PURE_EXTENDED_ARM';
  bool _downloading = false;
  void _reload() => setState(() {
    _report = _repository.getReport(widget.reportId);
    // Avoid an unhandled early failure before FutureBuilder reattaches.
    _report.ignore();
  });

  Future<void> _download() async {
    setState(() => _downloading = true);
    try {
      final text = await _repository.downloadReport(widget.reportId);
      if (!mounted) return;
      final saved = await saveExternalReportJson(
        text,
        '${widget.reportId}-ablation-report.json',
      );
      if (!mounted) return;
      if (saved) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Rule ablation JSON downloaded')),
        );
      } else {
        await showDialog<void>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Rule ablation JSON'),
            content: SizedBox(
              width: 700,
              child: SingleChildScrollView(child: SelectableText(text)),
            ),
            actions: [
              TextButton(
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: text));
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Report JSON copied')),
                    );
                  }
                },
                child: const Text('Copy JSON'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Close'),
              ),
            ],
          ),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Unable to download experiment: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Rule ablation · Development experiment'),
      actions: [
        IconButton(
          tooltip: 'Refresh experiment',
          onPressed: _reload,
          icon: const Icon(Icons.refresh),
        ),
        IconButton(
          tooltip: 'Download experiment JSON',
          onPressed: _downloading ? null : _download,
          icon: const Icon(Icons.download),
        ),
      ],
    ),
    body: FutureBuilder<RuleAblationReport>(
      future: _report,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return _ErrorPanel(error: snapshot.error!, retry: _reload);
        }
        final report = snapshot.data!;
        if (report.status != 'complete') {
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const _Notice(),
              Text(
                'Experiment status: ${report.status}. Completed metrics are unavailable.',
              ),
              if (report.json['error'] != null) Text('${report.json['error']}'),
            ],
          );
        }
        final trials = [...report.trials]
          ..sort((a, b) {
            final bySeed = a.seed.compareTo(b.seed);
            return bySeed != 0
                ? bySeed
                : rulePolicies
                      .indexOf(a.policy)
                      .compareTo(rulePolicies.indexOf(b.policy));
          });
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const _Notice(),
            SelectableText(report.id),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              key: const ValueKey('ablation-architecture'),
              initialValue: _architecture,
              decoration: const InputDecoration(
                labelText: 'Model architecture',
              ),
              items: const [
                DropdownMenuItem(
                  value: 'PURE_EXTENDED_ARM',
                  child: Text('Pure ARM'),
                ),
                DropdownMenuItem(value: 'EXTENDED_ARM', child: Text('Hybrid')),
              ],
              onChanged: (value) {
                if (value != null) setState(() => _architecture = value);
              },
            ),
            const SizedBox(height: 16),
            const Text(
              'External development diagnostics',
              style: TextStyle(fontSize: 20),
            ),
            const Text(
              'All seeds are shown. Thresholds were selected only on old Validation. '
              'These results do not establish independent generalization or incremental adaptation.',
            ),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: DataTable(
                columns: [
                  const DataColumn(label: Text('Policy / seed')),
                  for (final key in [
                    ...externalReportMetrics,
                    if (trials.any(
                      (t) => t.metrics(_architecture).containsKey('rocAuc'),
                    ))
                      'rocAuc',
                  ])
                    DataColumn(label: Text(_metricLabels[key]!)),
                ],
                rows: [
                  for (final trial in trials)
                    DataRow(
                      cells: [
                        DataCell(
                          Text('${_policy(trial.policy)} / ${trial.seed}'),
                        ),
                        for (final key in [
                          ...externalReportMetrics,
                          if (trials.any(
                            (t) =>
                                t.metrics(_architecture).containsKey('rocAuc'),
                          ))
                            'rocAuc',
                        ])
                          DataCell(
                            Text(
                              key == 'rocAuc'
                                  ? (trial.metrics(_architecture)[key] as num?)
                                            ?.toStringAsFixed(4) ??
                                        'Undefined'
                                  : _percent(trial.metrics(_architecture)[key]),
                            ),
                          ),
                      ],
                    ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            const Text(
              'FPR versus Recall — lower FPR, higher Recall',
              style: TextStyle(fontSize: 18),
            ),
            const Text(
              'Bars use a 0–100% scale. A smaller FPR alone is not a better detector.',
            ),
            for (final trial in trials)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('${_policy(trial.policy)} / ${trial.seed}'),
                      for (final metric in ['fpr', 'recall'])
                        _MetricBar(
                          label: _metricLabels[metric]!,
                          value: trial.metrics(_architecture)[metric] as num?,
                          harmful: metric == 'fpr',
                        ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 16),
            const Text(
              'False-positive / false-negative tradeoffs',
              style: TextStyle(fontSize: 18),
            ),
            const Text(
              'Changes are relative to Baseline with the same seed; negative means fewer cases.',
            ),
            for (final trial in trials.where((t) => t.policy != 'BASELINE'))
              Builder(
                builder: (context) {
                  final baseline = trials.singleWhere(
                    (t) => t.seed == trial.seed && t.policy == 'BASELINE',
                  );
                  final actual = trial.metrics(_architecture);
                  final original = baseline.metrics(_architecture);
                  final fp = (actual['fp'] as int) - (original['fp'] as int);
                  final fn = (actual['fn'] as int) - (original['fn'] as int);
                  String signed(int n) => n > 0 ? '+$n' : '$n';
                  return Text(
                    '${_policy(trial.policy)} / ${trial.seed}: '
                    'FP change ${signed(fp)}; FN change ${signed(fn)}',
                  );
                },
              ),
            const SizedBox(height: 16),
            const Text(
              'Confusion counts, intervals and checkpoints',
              style: TextStyle(fontSize: 18),
            ),
            for (final trial in trials)
              _TrialDetails(trial: trial, architecture: _architecture),
            const SizedBox(height: 16),
            const Text(
              'Protocol and provenance',
              style: TextStyle(fontSize: 18),
            ),
            if (report.json['rankingDerivation'] is Map)
              ExpansionTile(
                title: const Text(
                  'Ranking derivation — original checkpoints and thresholds',
                ),
                children: [
                  SelectableText(
                    const JsonEncoder.withIndent('  ')
                        .convert(report.json['rankingDerivation']),
                  ),
                ],
              ),
            SelectableText(
              'Created: ${report.json['createdAt']}\n'
              'Completed: ${report.json['completedAt']}\n'
              'Frozen manifest SHA256: ${report.json['frozenManifestSha256']}',
            ),
            for (final key in [
              'thresholdSelection',
              'externalUsage',
              'candidateControl',
              'sqlContext',
              'bootstrap',
            ])
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Text('$key: ${report.protocol[key]}'),
              ),
            const Text('Limitations', style: TextStyle(fontSize: 18)),
            for (final value in report.protocol['limitations'] as List)
              Text('• $value'),
            ExpansionTile(
              title: const Text('Verified input SHA256'),
              children: [
                SelectableText(
                  const JsonEncoder.withIndent('  ')
                      .convert(report.json['verifiedInputSha256']),
                ),
              ],
            ),
          ],
        );
      },
    ),
  );
}

class _TrialDetails extends StatelessWidget {
  final RuleAblationTrial trial;
  final String architecture;
  const _TrialDetails({required this.trial, required this.architecture});
  @override
  Widget build(BuildContext context) {
    final external = trial.metrics(architecture);
    final validation = trial.metrics(architecture, external: false);
    final result = trial.result(architecture);
    final intervals = ablationMap(result['confidenceIntervals'], 'intervals');
    return ExpansionTile(
      key: ValueKey('trial-${trial.json['id']}-$architecture'),
      title: Text('${_policy(trial.policy)} / ${trial.seed} · Details'),
      childrenPadding: const EdgeInsets.all(12),
      expandedCrossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Training rows: ${trial.json['trainingRows']} · '
          'External rows: ${external['total']} · Rules: ${trial.json['extendedRuleCount']}',
        ),
        if (trial.json['zeroExtendedRules'] == true)
          const Text('No retained rules: Pure scoring may be uninformative.'),
        SelectableText(
          'Validation-selected threshold: ${result['threshold']}\n'
          'Checkpoint: ${trial.json['checkpoint']}\n'
          'Checkpoint SHA256: ${trial.json['checkpointSha256']}',
        ),
        Text(
          'TP ${external['tp']} · FP ${external['fp']} · '
          'TN ${external['tn']} · FN ${external['fn']}',
        ),
        Text(
          'Old Validation: F1 ${_percent(validation['f1'])} · '
          'Recall ${_percent(validation['recall'])} · FPR ${_percent(validation['fpr'])}',
        ),
        for (final metric in externalReportMetrics)
          Builder(
            builder: (_) {
              final interval = ablationMap(intervals[metric], 'interval');
              return Text(
                '${_metricLabels[metric]}: ${_percent(external[metric])} · '
                '95% CI ${_percent(interval['lower'])}–${_percent(interval['upper'])} · '
                'valid/undefined replicates ${interval['validReplicates']}/${interval['undefinedReplicates']}',
              );
            },
          ),
        RankingEvaluationPanel(result: result),
      ],
    );
  }
}

class _MetricBar extends StatelessWidget {
  final String label;
  final num? value;
  final bool harmful;
  const _MetricBar({
    required this.label,
    required this.value,
    required this.harmful,
  });
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('$label: ${_percent(value)}'),
        if (value != null)
          LinearProgressIndicator(
            value: value!.toDouble(),
            color: harmful ? Colors.deepOrange : Colors.teal,
            minHeight: 8,
          ),
      ],
    ),
  );
}

class _Notice extends StatelessWidget {
  const _Notice();
  @override
  Widget build(BuildContext context) => Card(
    color: Theme.of(context).colorScheme.tertiaryContainer,
    child: const Padding(
      padding: EdgeInsets.all(12),
      child: Text(_researchNotice),
    ),
  );
}

class _ErrorPanel extends StatelessWidget {
  final Object error;
  final VoidCallback retry;
  const _ErrorPanel({required this.error, required this.retry});
  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('Unable to load rule ablation report'),
          Text('$error'),
          TextButton(onPressed: retry, child: const Text('Try again')),
        ],
      ),
    ),
  );
}
