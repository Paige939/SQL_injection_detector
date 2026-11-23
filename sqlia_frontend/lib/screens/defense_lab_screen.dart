import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/architecture_names.dart';
import '../models/defense_result.dart';
import '../services/defense_service.dart';

const _architectures = [
  'ARM_PROFILE',
  'RATIO_INCREMENTAL_ML',
  'FULL_FLOW',
  'PURE_EXTENDED_ARM',
  'EXTENDED_ARM',
];
const _algorithms = {
  'CURRENT_ONLINE_BAGGING_NB': 'Online Bagging (Naive Bayes)',
  'SGD': 'SGD / Incremental SVM',
  'HOEFFDING_TREE': 'Hoeffding Tree',
};

class DefenseLabScreen extends ConsumerStatefulWidget {
  final bool extendedOnly;
  const DefenseLabScreen({super.key, this.extendedOnly = false});

  @override
  ConsumerState<DefenseLabScreen> createState() => _DefenseLabScreenState();
}

class _DefenseLabScreenState extends ConsumerState<DefenseLabScreen> {
  final keyword = TextEditingController();
  final sample = TextEditingController(text: '1000');
  late String architecture;
  String algorithm = 'CURRENT_ONLINE_BAGGING_NB';
  Map<String, dynamic>? status, audit, report;
  DefenseResult? result;
  bool loading = false;
  String? error;
  List<String> get architectures => widget.extendedOnly
      ? const ['PURE_EXTENDED_ARM', 'EXTENDED_ARM']
      : _architectures;

  @override
  void initState() {
    super.initState();
    architecture = widget.extendedOnly ? 'PURE_EXTENDED_ARM' : 'EXTENDED_ARM';
    load();
  }

  @override
  void dispose() {
    keyword.dispose();
    sample.dispose();
    super.dispose();
  }

  Future<void> perform(Future<void> Function() operation) async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await operation();
    } on DioException catch (exception) {
      final data = exception.response?.data;
      final message = data is Map
          ? (data['message'] ?? data['errorCode'] ?? data['error'])?.toString()
          : null;
      if (mounted) {
        setState(
          () =>
              error = message ?? exception.message ?? 'Defense request failed.',
        );
      }
    } catch (exception) {
      if (mounted) setState(() => error = exception.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> load() => perform(() async {
    final service = ref.read(defenseServiceProvider);
    final values = await Future.wait([
      service.status(),
      service.audit(),
      service.latestEvaluation(),
    ]);
    if (mounted) {
      setState(() {
        status = values[0];
        audit = values[1];
        report = values[2];
      });
    }
  });

  Future<void> search() async {
    if (keyword.text.trim().isEmpty || keyword.text.length > 100) {
      setState(() => error = 'Search keyword must contain 1..100 characters.');
      return;
    }
    setState(() => result = null);
    await perform(() async {
      final service = ref.read(defenseServiceProvider);
      final value = await service.search(
        keyword: keyword.text,
        architecture: architecture,
        algorithm: architecture == 'EXTENDED_ARM'
            ? algorithm
            : 'CURRENT_ONLINE_BAGGING_NB',
      );
      if (mounted) setState(() => result = value);
      if (value.effectiveAction == 'block' && value.status == 'rejected') {
        await showBlockedPage(value);
      }
      final records = await service.audit();
      if (mounted) setState(() => audit = records);
    });
  }

  Future<void> resolveReview(
    String decision, {
    DefenseResult? pendingResult,
  }) async {
    final pending = pendingResult ?? result;
    if (pending == null) return;
    await perform(() async {
      final service = ref.read(defenseServiceProvider);
      final value = await service.resolveReview(
        requestId: pending.requestId,
        decision: decision,
      );
      if (mounted) setState(() => result = value);
      if (decision == 'block' &&
          value.effectiveAction == 'block' &&
          value.status == 'rejected') {
        await showBlockedPage(value);
      }
      final records = await service.audit();
      if (mounted) setState(() => audit = records);
    });
  }

  Future<void> showBlockedPage(DefenseResult value) async {
    if (!mounted) return;
    setState(() => result = null);
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => _DefenseBlockedScreen(result: value),
      ),
    );
  }

  Future<void> evaluate() async {
    final count = int.tryParse(sample.text);
    if (count == null || count < 1 || count > 10000) {
      setState(() => error = 'Evaluation sample size must be 1..10000.');
      return;
    }
    await perform(() async {
      final value = await ref
          .read(defenseServiceProvider)
          .evaluate(extendedOnly: widget.extendedOnly, sampleSize: count);
      if (mounted) setState(() => report = value);
    });
  }

  bool visibleArchitecture(Object? value) => architectures.contains(value);

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            widget.extendedOnly
                ? 'Defense Lab — Two Extended ARM methods'
                : 'Defense Lab — All five architectures',
          ),
          actions: [
            IconButton(
              onPressed: loading ? null : load,
              icon: const Icon(Icons.refresh),
              tooltip: 'Refresh lab status',
            ),
          ],
          bottom: const TabBar(
            tabs: [
              Tab(text: 'Protected search'),
              Tab(text: 'Audit & gate evidence'),
              Tab(text: 'Test & algorithms'),
            ],
          ),
        ),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(
                status == null
                    ? 'Waiting for defense backend. Search is disabled until status is available.'
                    : 'Mode: ${status!['mode']} | Review: user decision\n'
                          'Model version: ${status!['modelVersion']} | '
                          'Warmup: ${status!['trainingSamples']} rows\n'
                          'Only an allowed request runs the parameterized product search. '
                          'This lab does not update models.',
              ),
            ),
            if (loading) const LinearProgressIndicator(),
            if (error != null)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            Expanded(
              child: TabBarView(
                children: [searchTab(), auditTab(), evaluationTab()],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget searchTab() => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      const Text(
        'Search by product name or exact price_cents value. For example, enter '
        'Keyboard or 10000. Allowed searches show matching products.',
      ),
      const SizedBox(height: 16),
      DropdownButtonFormField<String>(
        initialValue: architecture,
        decoration: const InputDecoration(
          labelText: 'Defense architecture',
          border: OutlineInputBorder(),
        ),
        items: [
          for (final value in architectures)
            DropdownMenuItem(
              value: value,
              child: Text(architectureDisplayName(value)),
            ),
        ],
        onChanged: loading
            ? null
            : (value) => setState(() {
                architecture = value!;
                result = null;
              }),
      ),
      if (architecture == 'EXTENDED_ARM') ...[
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: algorithm,
          decoration: const InputDecoration(
            labelText: 'Extended ARM algorithm',
            border: OutlineInputBorder(),
          ),
          items: [
            for (final entry in _algorithms.entries)
              DropdownMenuItem(value: entry.key, child: Text(entry.value)),
          ],
          onChanged: loading
              ? null
              : (value) => setState(() {
                  algorithm = value!;
                  result = null;
                }),
        ),
      ],
      const SizedBox(height: 16),
      TextField(
        controller: keyword,
        maxLines: 2,
        decoration: const InputDecoration(
          labelText: 'Product name or price_cents (1..100 characters)',
          hintText: 'Keyboard or 10000',
          border: OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 12),
      FilledButton.icon(
        onPressed: loading || status == null ? null : search,
        icon: const Icon(Icons.shield_outlined),
        label: const Text('Search through defense gate'),
      ),
      const SizedBox(height: 12),
      const Text(
        'Block denies the search. Review pauses until you choose Allow or Block. '
        'Allow runs a parameterized product lookup; it does not execute your input as SQL.',
      ),
      if (result != null)
        _ResultCard(
          result: result!,
          busy: loading,
          onAllowReview: result!.status == 'review_pending'
              ? () => resolveReview('allow', pendingResult: result)
              : null,
          onBlockReview: result!.status == 'review_pending'
              ? () => resolveReview('block', pendingResult: result)
              : null,
        ),
    ],
  );

  Widget auditTab() {
    final records = audit?['records'] as List? ?? [];
    final summary = audit?['summary'] as Map?;
    final groups = summary?['groups'] as List? ?? [];
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const Text(
          'Latest 200 requests, persisted across restarts. Raw keywords are not stored. '
          'Models from different startup versions are grouped separately.',
        ),
        const SizedBox(height: 12),
        const ExpansionTile(
          title: Text('How is “no product query” verified?'),
          children: [
            Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'Block decisions and pending reviews do not call the product service. '
                'A review calls it only after the user selects Allow. This page shows actual '
                'recorded attempts, not a live test-success badge. Audit writes are separate '
                'from product queries; an incomplete attempt is not proof of completion.',
              ),
            ),
          ],
        ),
        if (summary != null) Text(summary['note'] as String),
        for (final value in groups)
          if (visibleArchitecture((value as Map)['architecture']))
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Text(
                  '${architectureDisplayName(value['architecture'] as String)} / ${value['algorithm']}\n'
                  'Version: ${value['modelVersion']}\n'
                  'Requests: ${value['requests']} | Rejected: ${value['rejected']} | '
                  'Incomplete/failed: ${value['incompleteOrFailed']}\n'
                  'Rejected product-query attempts: ${value['rejectedQueryAttempts']}\n'
                  'Total p50: ${milliseconds(value['totalP50Ms'])} | p95: ${milliseconds(value['totalP95Ms'])}',
                ),
              ),
            ),
        if (!records.any(
          (value) => visibleArchitecture((value as Map)['architecture']),
        ))
          const Text('No audit records for this comparison mode yet.'),
        for (final value in records)
          if (visibleArchitecture((value as Map)['architecture']))
            _ResultCard(
              result: DefenseResult.fromJson(Map<String, dynamic>.from(value)),
              busy: loading,
              onAllowReview: value['status'] == 'review_pending'
                  ? () => resolveReview(
                      'allow',
                      pendingResult: DefenseResult.fromJson(
                        Map<String, dynamic>.from(value),
                      ),
                    )
                  : null,
              onBlockReview: value['status'] == 'review_pending'
                  ? () => resolveReview(
                      'block',
                      pendingResult: DefenseResult.fromJson(
                        Map<String, dynamic>.from(value),
                      ),
                    )
                  : null,
            ),
      ],
    );
  }

  Widget evaluationTab() {
    final rows = report?['results'] as List? ?? [];
    final visibleRows = rows
        .where((value) => visibleArchitecture((value as Map)['architecture']))
        .toList();
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const Text(
          'Uses the same held-out Test records for each choice, without model updates or product queries. '
          'Extended ARM compares Online Bagging, SGD and Hoeffding Tree, using the same rule features and warmup rows. '
          'This is a fixed-session diagnostic, not an independent blind-test result.',
        ),
        const SizedBox(height: 16),
        TextField(
          controller: sample,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'Test records (1..10000)',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: loading || status == null ? null : evaluate,
          icon: const Icon(Icons.compare_arrows),
          label: Text(
            widget.extendedOnly
                ? 'Compare both methods and three algorithms'
                : 'Compare five architectures and three algorithms',
          ),
        ),
        const SizedBox(height: 16),
        if (report == null || report!['status'] == 'missing')
          const Text(
            'No saved defense evaluation yet. Run the comparison to create a report.',
          ),
        if (rows.isNotEmpty) ...[
          Text(
            'Saved report: ${report!['timestamp']} | Version: ${report!['modelVersion']}\n'
            'Mode: ${report!['evaluationMode']} | Records: ${report!['sampleSize']}',
          ),
          if (report!['modelVersion'] != status?['modelVersion'])
            const Text(
              'This saved report belongs to a previous startup model version.',
            ),
          if (!widget.extendedOnly && report!['extendedOnly'] == true)
            const Text(
              'Saved report contains B-mode only. Run A-mode to include all five architectures.',
            ),
          Text(report!['note'] as String),
        ],
        for (final value in visibleRows)
          _MetricCard(data: Map<String, dynamic>.from(value as Map)),
      ],
    );
  }
}

String milliseconds(Object? value) =>
    value == null ? 'undefined' : '${(value as num).toStringAsFixed(3)} ms';
String percentage(Object? value) => value == null
    ? 'undefined'
    : '${((value as num) * 100).toStringAsFixed(2)}%';

class _ResultCard extends StatelessWidget {
  final DefenseResult result;
  final bool busy;
  final VoidCallback? onAllowReview;
  final VoidCallback? onBlockReview;

  const _ResultCard({
    required this.result,
    this.busy = false,
    this.onAllowReview,
    this.onBlockReview,
  });

  @override
  Widget build(BuildContext context) {
    final pendingReview = result.status == 'review_pending';
    final action = switch (result.effectiveAction) {
      'allow' =>
        result.recommendedAction == 'review'
            ? 'Allowed by your decision'
            : 'Predicted benign — search allowed',
      'block' => 'Blocked',
      'review' => pendingReview ? 'Needs your decision' : 'Review required',
      'deny' => 'Denied',
      _ => 'Unavailable',
    };
    final queryStatus = result.businessQueryCompleted
        ? 'Product lookup completed'
        : result.businessQueryAttempted
        ? 'Product lookup attempted; completion is unconfirmed'
        : 'Product lookup not run';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(action, style: Theme.of(context).textTheme.titleMedium),
            Text(queryStatus),
            if (pendingReview)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'Choose Allow to search, or Block to stop the request.',
                ),
              ),
            if (result.reason != null)
              Text('Reason: ${_reasonLabel(result.reason!)}'),
            if (result.errorCode != null) Text('Error: ${result.errorCode}'),
            if (result.score != null && result.threshold != null)
              Text(
                'Detection score: ${percentage(result.score)} '
                '(threshold ${percentage(result.threshold)})',
              ),
            if (pendingReview &&
                onAllowReview != null &&
                onBlockReview != null) ...[
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: busy ? null : onAllowReview,
                      icon: const Icon(Icons.check),
                      label: const Text('Allow search'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: busy ? null : onBlockReview,
                      icon: const Icon(Icons.block),
                      label: const Text('Block'),
                    ),
                  ),
                ],
              ),
            ],
            if (result.status == 'complete' && result.products.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('No matching products.'),
              ),
            for (final product in result.products)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(product.name),
                subtitle: Text(product.category),
                trailing: Text(
                  'NT\$ ${(product.priceCents / 100).toStringAsFixed(2)}',
                ),
              ),
            if (result.matchedRules.isNotEmpty)
              ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: Text('Matched rules (${result.matchedRules.length})'),
                children: [
                  for (final rule in result.matchedRules)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text('${rule['description']}'),
                      subtitle: Text(
                        'Confidence ${percentage(rule['confidence'])} · '
                        'Support ${percentage(rule['support'])}',
                      ),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  String _reasonLabel(String reason) => switch (reason) {
    'MANUAL_REVIEW_REQUIRED' => 'Manual review required',
    'USER_ALLOWED_REVIEW' => 'You allowed this reviewed search',
    'USER_BLOCKED_REVIEW' => 'You blocked this reviewed search',
    'DETECTION_THRESHOLD_EXCEEDED' => 'Detection threshold exceeded',
    _ => reason,
  };
}

class _DefenseBlockedScreen extends StatelessWidget {
  final DefenseResult result;
  const _DefenseBlockedScreen({required this.result});

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Search blocked')),
    body: Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.block,
              size: 56,
              color: Theme.of(context).colorScheme.error,
            ),
            const SizedBox(height: 12),
            Text(
              'This search was blocked.',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(
              result.businessQueryAttempted
                  ? 'The product lookup was attempted; check the audit record for its status.'
                  : 'The product lookup was not run. No product results were returned.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Back to search'),
            ),
          ],
        ),
      ),
    ),
  );
}

class _MetricCard extends StatelessWidget {
  final Map<String, dynamic> data;
  const _MetricCard({required this.data});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${architectureDisplayName(data['architecture'] as String)} / ${data['algorithm']}',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          Text(
            'TP ${data['tp']} | FP ${data['fp']} | TN ${data['tn']} | FN ${data['fn']}',
          ),
          Text(
            'Accuracy ${percentage(data['accuracy'])} | Precision ${percentage(data['precision'])} | Recall ${percentage(data['recall'])}',
          ),
          Text(
            'F1 ${percentage(data['f1'])} | Binary FPR ${percentage(data['fpr'])}',
          ),
          Text(
            'Benign block/review ${percentage(data['benignDenialRate'])} | Attack pass ${percentage(data['attackPassRate'])} | Review ${data['reviewCount']}',
          ),
          Text(
            'Threshold ${data['threshold']} | Detection p50 ${milliseconds(data['detectionP50Ms'])} | p95 ${milliseconds(data['detectionP95Ms'])}',
          ),
        ],
      ),
    ),
  );
}
