import 'dart:convert';

import '../widgets/ranking_evaluation_panel.dart';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/api_client.dart';
import '../models/external_report.dart';
import '../services/external_report_download.dart';
import '../services/external_report_service.dart';
import '../services/rule_ablation_report_service.dart';
import 'rule_ablation_report_screen.dart';

class ExternalReportListScreen extends StatefulWidget {
  final bool extendedOnly;
  final ExternalReportsRepository? repository;
  final RuleAblationReportsRepository? ablationRepository;

  const ExternalReportListScreen({
    super.key,
    required this.extendedOnly,
    this.repository,
    this.ablationRepository,
  });

  @override
  State<ExternalReportListScreen> createState() =>
      _ExternalReportListScreenState();
}

class _ExternalReportListScreenState extends State<ExternalReportListScreen> {
  late final ExternalReportsRepository _repository;
  late Future<List<ExternalReport>> _reports;
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    _repository = widget.repository ?? ExternalReportService(ApiClient());
    _reports = _repository.listReports();
  }

  void _reload() {
    setState(() {
      _reports = _repository.listReports();
    });
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            widget.extendedOnly
                ? 'External generalization · Pure / Hybrid'
                : 'External generalization reports',
          ),
          bottom: TabBar(
            onTap: (index) => setState(() => _tab = index),
            tabs: const [
              Tab(text: 'Frozen evaluation'),
              Tab(text: 'Rule ablation'),
            ],
          ),
          actions: [
            if (_tab == 0)
              IconButton(
                tooltip: 'Refresh saved reports',
                onPressed: _reload,
                icon: const Icon(Icons.refresh),
              ),
          ],
        ),
        body: _tab == 1
            ? RuleAblationReportList(repository: widget.ablationRepository)
            : FutureBuilder<List<ExternalReport>>(
                future: _reports,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return _MessagePanel(
                      icon: Icons.error_outline,
                      title: 'Unable to load saved external reports',
                      message: _errorMessage(snapshot.error!),
                      actionLabel: 'Try again',
                      onAction: _reload,
                    );
                  }
                  final reports = snapshot.data!;
                  final visible = reports
                      .where(
                        (report) => _matchesMode(report, widget.extendedOnly),
                      )
                      .toList(growable: false);
                  if (visible.isEmpty) {
                    return _MessagePanel(
                      icon: Icons.inbox_outlined,
                      title: reports.isEmpty
                          ? 'No saved external reports yet'
                          : 'No reports match this architecture mode',
                      message: reports.isEmpty
                          ? 'This screen reads saved reports from the experiments folder. '
                                'No models are trained or evaluated here.'
                          : 'Choose all architectures to review the available saved reports.',
                      actionLabel: reports.isEmpty
                          ? null
                          : 'Show all architectures',
                      onAction: reports.isEmpty
                          ? null
                          : () => Navigator.of(context).pushReplacement(
                              MaterialPageRoute(
                                builder: (_) => ExternalReportListScreen(
                                  extendedOnly: false,
                                  repository: _repository,
                                  ablationRepository: widget.ablationRepository,
                                ),
                              ),
                            ),
                    );
                  }
                  return RefreshIndicator(
                    onRefresh: () async => _reload(),
                    child: ListView(
                      padding: const EdgeInsets.all(16),
                      children: [
                        const Text(
                          'Saved, read-only evaluation snapshots. Opening a report never '
                          'loads live models or starts evaluation.',
                        ),
                        const SizedBox(height: 12),
                        for (final report in visible)
                          Card(
                            margin: const EdgeInsets.only(bottom: 10),
                            child: ListTile(
                              leading: Icon(_statusIcon(report.status)),
                              title: Text(report.id),
                              subtitle: Text(
                                '${_statusLabel(report.status)} · '
                                '${_architectureNames(report, widget.extendedOnly)}',
                              ),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () => Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => ExternalReportDetailScreen(
                                    reportId: report.id,
                                    extendedOnly: widget.extendedOnly,
                                    repository: _repository,
                                  ),
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  );
                },
              ),
      ),
    );
  }
}

class ExternalReportDetailScreen extends StatefulWidget {
  final String reportId;
  final bool extendedOnly;
  final ExternalReportsRepository? repository;

  const ExternalReportDetailScreen({
    super.key,
    required this.reportId,
    required this.extendedOnly,
    this.repository,
  });

  @override
  State<ExternalReportDetailScreen> createState() =>
      _ExternalReportDetailScreenState();
}

class _ExternalReportDetailScreenState
    extends State<ExternalReportDetailScreen> {
  late final ExternalReportsRepository _repository;
  late Future<ExternalReport> _report;
  bool _downloading = false;

  @override
  void initState() {
    super.initState();
    _repository = widget.repository ?? ExternalReportService(ApiClient());
    _report = _repository.getReport(widget.reportId);
  }

  void _reload() {
    setState(() {
      _report = _repository.getReport(widget.reportId);
    });
  }

  Future<void> _showJson() async {
    setState(() => _downloading = true);
    try {
      final json = await _repository.downloadReport(widget.reportId);
      if (!mounted) return;
      final downloaded = await saveExternalReportJson(
        json,
        '${widget.reportId}-external-report.json',
      );
      if (!mounted) return;
      if (downloaded) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('External report JSON downloaded')),
        );
        return;
      }
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('External report JSON'),
          content: SizedBox(
            width: 700,
            child: SingleChildScrollView(
              child: SelectableText(
                json,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ),
          ),
          actions: [
            TextButton.icon(
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: json));
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Report JSON copied')),
                  );
                }
              },
              icon: const Icon(Icons.copy),
              label: const Text('Copy JSON'),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Close'),
            ),
          ],
        ),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Unable to download report: ${_errorMessage(error)}'),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _downloading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('External generalization report'),
        actions: [
          IconButton(
            tooltip: 'Refresh report',
            onPressed: _reload,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Download report JSON',
            onPressed: _downloading ? null : _showJson,
            icon: _downloading
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.download_outlined),
          ),
        ],
      ),
      body: FutureBuilder<ExternalReport>(
        future: _report,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return _MessagePanel(
              icon: Icons.error_outline,
              title: 'Unable to load saved report',
              message: _errorMessage(snapshot.error!),
              actionLabel: 'Try again',
              onAction: _reload,
            );
          }
          return _ReportDetails(
            report: snapshot.data!,
            extendedOnly: widget.extendedOnly,
          );
        },
      ),
    );
  }
}

class _ReportDetails extends StatelessWidget {
  final ExternalReport report;
  final bool extendedOnly;

  const _ReportDetails({required this.report, required this.extendedOnly});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final dataset = report.dataset;
    final allModels = report.models;
    final models = extendedOnly
        ? allModels.where(_isExtendedArchitecture).toList(growable: false)
        : allModels;
    final limitations = report.limitations;
    final blind = _isIndependentBlindEligible(report);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Chip(
              avatar: Icon(_statusIcon(report.status), size: 18),
              label: Text(_statusLabel(report.status)),
            ),
            Chip(
              avatar: Icon(
                blind ? Icons.verified_user_outlined : Icons.warning_amber,
                size: 18,
              ),
              label: Text(
                blind
                    ? 'Independent blind external test'
                    : 'Retrospective / external overlap-filtered',
              ),
            ),
          ],
        ),
        SelectableText(
          report.id,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text('Created: ${report.json['createdAt']}'),
        if (report.json['completedAt'] is String)
          Text('Completed: ${report.json['completedAt']}'),
        const SizedBox(height: 16),
        _statusDescription(report.status, colorScheme),
        if (report.json['rankingDerivation'] is Map)
          _SectionCard(
            title: 'Ranking-derived report — original model and threshold',
            children: [
              const Text(
                'Additional evaluation of saved models, not a new independent blind test.',
              ),
              _jsonBlock(
                'Source and verification',
                report.json['rankingDerivation'],
              ),
            ],
          ),
        _SectionCard(
          title: 'Evaluation and update policy',
          children: [
            _KeyValue(
              label: 'Evaluation mode',
              value: report.json['evaluationMode'].toString(),
            ),
            for (final entry in Map<String, dynamic>.from(
              report.json['policy'] as Map,
            ).entries)
              _KeyValue(label: entry.key, value: entry.value.toString()),
            if (report.status != 'complete')
              for (final key in ['error', 'reason', 'blockedReason'])
                if (report.json[key] is String)
                  _KeyValue(
                    label: 'Report note',
                    value: report.json[key] as String,
                  ),
          ],
        ),
        if (report.status == 'complete' && dataset != null) ...[
          _SectionCard(
            title: 'Dataset and provenance',
            children: [
              _KeyValue(label: 'Source', value: dataset['source'].toString()),
              _KeyValue(
                label: 'Dataset SHA-256',
                value: dataset['fileSha256'].toString(),
                selectable: true,
              ),
              _KeyValue(
                label: 'Original rows',
                value: dataset['originalRows'].toString(),
              ),
              _KeyValue(
                label: 'Evaluated rows',
                value: dataset['evaluatedRows'].toString(),
              ),
              _KeyValue(
                label: 'Excluded rows',
                value: dataset['excludedRows'].toString(),
              ),
              _KeyValue(
                label: 'Benign / malicious',
                value:
                    '${dataset['benignCount']} / ${dataset['maliciousCount']}',
              ),
              _KeyValue(
                label: 'Audit report SHA-256',
                value: dataset['auditReportSha256'].toString(),
                selectable: true,
              ),
              _jsonBlock('Exclusion counts', dataset['exclusionCounts']),
            ],
          ),
        ],
        _SectionCard(
          title: 'Source provenance',
          children: report.json['provenance'] is Map
              ? [_jsonBlock('Provenance details', report.json['provenance'])]
              : const [
                  Text('Provenance is not available for this report state.'),
                ],
        ),
        _SectionCard(
          title: 'Overlap and independence limitations',
          children: [
            if (limitations.isEmpty)
              const Text('No limitations were listed in this report.')
            else
              for (final limitation in limitations)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: const Icon(Icons.info_outline),
                  title: Text(limitation),
                ),
            const Text(
              'Dataset origin and independence are not inferred from the source '
              'name. Review the saved provenance and overlap audit before '
              'interpreting results as blind generalization.',
            ),
          ],
        ),
        if (report.status == 'complete') ...[
          if (models.isEmpty)
            const _SectionCard(
              title: 'Model metrics',
              children: [
                Text(
                  'This report contains no model results for the selected mode.',
                ),
              ],
            ),
          if (models.isNotEmpty) _MetricComparisonChart(models: models),
          for (final model in models) _ModelCard(model: model),
          const _SectionCard(
            title: 'Historical test comparison',
            children: [
              Text(
                'Historical test comparison unavailable for this frozen report.',
              ),
            ],
          ),
        ],
        if (report.status != 'complete' && dataset != null)
          _SectionCard(
            title: 'Available dataset information',
            children: [
              if (dataset.isEmpty)
                const Text(
                  'Dataset details are not available for this report state.',
                ),
              if (dataset['source'] is String)
                _KeyValue(label: 'Source', value: dataset['source'] as String),
              for (final key in [
                'originalRows',
                'evaluatedRows',
                'benignCount',
                'maliciousCount',
                'excludedRows',
              ])
                if (dataset[key] is num)
                  _KeyValue(label: key, value: dataset[key].toString()),
              if (dataset['exclusionCounts'] is Map)
                _jsonBlock('Exclusion counts', dataset['exclusionCounts']),
            ],
          ),
      ],
    );
  }
}

class _ModelCard extends StatelessWidget {
  final Map<String, dynamic> model;

  const _ModelCard({required this.model});

  @override
  Widget build(BuildContext context) {
    final architecture = model['architecture'] as String;
    final metrics = Map<String, dynamic>.from(model['metrics'] as Map);
    final intervals = Map<String, dynamic>.from(
      model['confidenceIntervals'] as Map,
    );
    final trainingPolicy = model['trainingPolicy'];
    final policyVersion = trainingPolicy is Map
        ? trainingPolicy['version'] ??
              const JsonEncoder.withIndent('  ').convert(trainingPolicy)
        : trainingPolicy;
    final bySource = model['bySource'];
    final baseline = model['baselineComparison'];

    return _SectionCard(
      title: _architectureLabel(architecture),
      children: [
        Text('Checkpoint / threshold / policy version'),
        _KeyValue(
          label: 'Checkpoint SHA-256',
          value: model['checkpointSha256'].toString(),
          selectable: true,
        ),
        _KeyValue(
          label: 'Threshold',
          value: _preciseNumber(model['threshold'] as num),
        ),
        _KeyValue(
          label: 'Training policy version',
          value: policyVersion.toString(),
        ),
        const SizedBox(height: 12),
        Text('Confusion matrix', style: Theme.of(context).textTheme.titleSmall),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            _CountChip(label: 'Total', value: metrics['total']),
            _CountChip(label: 'TP', value: metrics['tp']),
            _CountChip(label: 'FP', value: metrics['fp']),
            _CountChip(label: 'TN', value: metrics['tn']),
            _CountChip(label: 'FN', value: metrics['fn']),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          'Metrics and 95% confidence intervals',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        for (final metric in externalReportMetrics)
          _MetricRow(
            label: _metricLabel(metric),
            value: metrics[metric] as num,
            interval: Map<String, dynamic>.from(intervals[metric] as Map),
          ),
        RankingEvaluationPanel(result: model),
        if (bySource is Map && bySource.isNotEmpty) ...[
          const Divider(height: 28),
          Text(
            'Per-source results',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          for (final entry in bySource.entries)
            _SourceMetrics(
              source: entry.key.toString(),
              metrics: Map<String, dynamic>.from(entry.value as Map),
            ),
        ],
        if (baseline is Map) ...[
          const Divider(height: 28),
          _BaselineComparison(comparison: Map<String, dynamic>.from(baseline)),
        ],
      ],
    );
  }
}

class _MetricRow extends StatelessWidget {
  final String label;
  final num value;
  final Map<String, dynamic> interval;

  const _MetricRow({
    required this.label,
    required this.value,
    required this.interval,
  });

  @override
  Widget build(BuildContext context) {
    final lower = interval['lower'];
    final upper = interval['upper'];
    final valid = interval['validReplicates'];
    final undefined = interval['undefinedReplicates'];
    final range = lower == null && upper == null
        ? 'Not estimable'
        : '${_optionalNumber(lower)}–${_optionalNumber(upper)}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(flex: 2, child: Text(label)),
          Expanded(
            flex: 3,
            child: Text(
              '${_number(value)} · $range (95% CI; '
              '$valid valid, $undefined undefined replicates)',
            ),
          ),
        ],
      ),
    );
  }
}

class _MetricComparisonChart extends StatelessWidget {
  final List<Map<String, dynamic>> models;

  const _MetricComparisonChart({required this.models});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return _SectionCard(
      title: 'Selected-model metric comparison',
      children: [
        const Text(
          'Bars show the saved metric values on a 0–1 scale; they do not imply '
          'statistical significance.',
        ),
        const SizedBox(height: 12),
        for (final metric in [
          'accuracy',
          'f1',
          'precision',
          'recall',
          'fpr',
          'averagePrecision',
          if (models.every(
            (model) => (model['metrics'] as Map)['rocAuc'] is num,
          ))
            'rocAuc',
        ]) ...[
          Padding(
            padding: const EdgeInsets.only(top: 8, bottom: 4),
            child: Text(
              _metricLabel(metric),
              style: Theme.of(context).textTheme.titleSmall,
            ),
          ),
          for (var index = 0; index < models.length; index++)
            _ComparisonBar(
              metric: metric,
              architecture: models[index]['architecture'] as String,
              value: (models[index]['metrics'] as Map)[metric] as num,
              color: index.isEven ? colors.primary : colors.tertiary,
            ),
        ],
      ],
    );
  }
}

class _ComparisonBar extends StatelessWidget {
  final String metric;
  final String architecture;
  final num value;
  final Color color;

  const _ComparisonBar({
    required this.metric,
    required this.architecture,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    final label =
        '${_architectureLabel(architecture)} ${_metricLabel(metric)} '
        '${_number(value)}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Semantics(
        label: label,
        child: ExcludeSemantics(
          child: Row(
            children: [
              SizedBox(
                width: 145,
                child: Text(
                  _architectureLabel(architecture),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              Expanded(
                child: Tooltip(
                  message: label,
                  child: LinearProgressIndicator(
                    value: value.toDouble().clamp(0, 1),
                    minHeight: 12,
                    color: color,
                    backgroundColor: color.withValues(alpha: 0.15),
                  ),
                ),
              ),
              SizedBox(
                width: 64,
                child: Text(_number(value), textAlign: TextAlign.end),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SourceMetrics extends StatelessWidget {
  final String source;
  final Map<String, dynamic> metrics;

  const _SourceMetrics({required this.source, required this.metrics});

  @override
  Widget build(BuildContext context) {
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      title: Text(source),
      children: [
        for (final metric in externalReportMetrics)
          _KeyValue(
            label: _metricLabel(metric),
            value: _number(metrics[metric] as num),
          ),
      ],
    );
  }
}

class _BaselineComparison extends StatelessWidget {
  final Map<String, dynamic> comparison;

  const _BaselineComparison({required this.comparison});

  @override
  Widget build(BuildContext context) {
    final note =
        comparison['distributionNote'] ??
        comparison['distributionDifferenceNote'];
    if (comparison['compatible'] != true ||
        note is! String ||
        note.trim().isEmpty) {
      return const Text(
        'Old-test comparison omitted: compatibility and an explicit differing '
        'distribution note were not both provided.',
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Compatible old-test comparison',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        Text('Distribution difference: $note'),
        const SizedBox(height: 8),
        SelectableText(
          const JsonEncoder.withIndent('  ').convert(comparison),
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
        ),
      ],
    );
  }
}

class _SectionCard extends StatelessWidget {
  final String title;
  final List<Widget> children;

  const _SectionCard({required this.title, required this.children});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(top: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _KeyValue extends StatelessWidget {
  final String label;
  final String value;
  final bool selectable;

  const _KeyValue({
    required this.label,
    required this.value,
    this.selectable = false,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 170,
            child: Text(label, style: Theme.of(context).textTheme.labelLarge),
          ),
          Expanded(
            child: selectable
                ? SelectableText(value)
                : Text(value, softWrap: true),
          ),
        ],
      ),
    );
  }
}

class _CountChip extends StatelessWidget {
  final String label;
  final dynamic value;

  const _CountChip({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Chip(label: Text('$label: $value'));
}

class _MessagePanel extends StatelessWidget {
  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  const _MessagePanel({
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48),
            const SizedBox(height: 12),
            Text(title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(message, textAlign: TextAlign.center),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 12),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}

Widget _statusDescription(String status, ColorScheme colors) {
  final (icon, text) = switch (status) {
    'running' => (
      Icons.hourglass_top,
      'This saved report is still running. Completed evaluation metrics are not available yet.',
    ),
    'failed' => (
      Icons.error_outline,
      'This report failed. No completed evaluation metrics are presented.',
    ),
    'blocked' => (
      Icons.block,
      'This evaluation was blocked. No completed evaluation metrics are presented.',
    ),
    _ => (
      Icons.check_circle_outline,
      'This is a completed, saved evaluation report.',
    ),
  };
  return Card(
    color: colors.surfaceContainerHighest,
    child: ListTile(leading: Icon(icon), title: Text(text)),
  );
}

bool _matchesMode(ExternalReport report, bool extendedOnly) {
  if (!extendedOnly || report.status != 'complete') return true;
  return report.models.any(_isExtendedArchitecture);
}

bool _isExtendedArchitecture(Map<String, dynamic> model) => const {
  'PURE_EXTENDED_ARM',
  'EXTENDED_ARM',
  'PURE',
  'HYBRID',
}.contains(model['architecture']);

String _architectureNames(ExternalReport report, bool extendedOnly) {
  final names = report.models
      .where((model) => !extendedOnly || _isExtendedArchitecture(model))
      .map((model) => model['architecture'])
      .whereType<String>()
      .map(_architectureLabel)
      .toList();
  return names.isEmpty ? 'model results not available' : names.join(', ');
}

String _architectureLabel(String architecture) => switch (architecture) {
  'PURE_EXTENDED_ARM' || 'PURE' => 'Pure Extended ARM',
  'EXTENDED_ARM' || 'HYBRID' => 'Hybrid Extended ARM',
  _ => architecture.replaceAll('_', ' '),
};

bool _isIndependentBlindEligible(ExternalReport report) {
  if (report.json['blindTestEligible'] != true) return false;
  final reportContext = [
    ...report.limitations,
    jsonEncode(report.json['provenance']),
  ].join(' ').toLowerCase();
  return !RegExp(
        r'overlap|retrospective|kaggle|duplicate|contamination|not blind|unverified origin|origin unverified|independence[ _-]unverified|independent origin not verified',
      ).hasMatch(reportContext) &&
      !_hasNegativeBlindProvenance(report.json['provenance']);
}

bool _hasNegativeBlindProvenance(dynamic value) {
  if (value is Map) {
    for (final entry in value.entries) {
      final key = entry.key.toString().toLowerCase();
      if (RegExp(r'independent|independence|blind').hasMatch(key) &&
          (entry.value == false || entry.value == 'unverified')) {
        return true;
      }
      if (_hasNegativeBlindProvenance(entry.value)) return true;
    }
  } else if (value is List) {
    return value.any(_hasNegativeBlindProvenance);
  }
  return false;
}

IconData _statusIcon(String status) => switch (status) {
  'complete' => Icons.check_circle_outline,
  'running' => Icons.hourglass_top,
  'failed' => Icons.error_outline,
  'blocked' => Icons.block,
  _ => Icons.help_outline,
};

String _statusLabel(String status) => switch (status) {
  'complete' => 'Complete',
  'running' => 'Running',
  'failed' => 'Failed',
  'blocked' => 'Blocked',
  _ => status,
};

String _metricLabel(String metric) => switch (metric) {
  'f1' => 'F1',
  'fpr' => 'False-positive rate',
  'averagePrecision' => 'Average precision (AP)',
  'rocAuc' => 'ROC-AUC',
  _ => metric[0].toUpperCase() + metric.substring(1),
};

String _number(num value) => value.toStringAsFixed(4);

String _preciseNumber(num value) => value.toString();

String _optionalNumber(dynamic value) =>
    value is num ? _number(value) : 'undefined';

Widget _jsonBlock(String title, dynamic value) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 6),
  child: Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
      SelectableText(
        const JsonEncoder.withIndent('  ').convert(value),
        style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
      ),
    ],
  ),
);

String _errorMessage(Object error) {
  final text = error.toString();
  return text.startsWith('Exception: ') ? text.substring(11) : text;
}
