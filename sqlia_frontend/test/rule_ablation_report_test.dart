import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlia_frontend/core/api_client.dart';
import 'package:sqlia_frontend/models/external_report.dart';
import 'package:sqlia_frontend/models/rule_ablation_report.dart';
import 'package:sqlia_frontend/screens/external_report_screen.dart';
import 'package:sqlia_frontend/screens/rule_ablation_report_screen.dart';
import 'package:sqlia_frontend/services/external_report_service.dart';
import 'package:sqlia_frontend/services/rule_ablation_report_service.dart';

const _id = 'rule-ablation-fixture';

Map<String, dynamic> _fixture({String status = 'complete'}) => {
  'runId': _id,
  'schemaVersion': 'sqlia-rule-ablation-v1',
  'status': status,
  'createdAt': '2026-10-25T00:00:00Z',
  if (status == 'complete') ...{
    'completedAt': '2026-10-25T01:00:00Z',
    'frozenManifestSha256': 'a' * 64,
    'verifiedInputSha256': {'train.csv': 'b' * 64},
    'protocol': {
      'websiteModelPromoted': false,
      'oldFinalTestRead': false,
      'seeds': [42, 43],
      'policies': rulePolicies,
      'architectures': ruleArchitectures,
      'limitations': ['Previously disclosed labels; no independent holdout.'],
      for (final key in [
        'thresholdSelection',
        'externalUsage',
        'candidateControl',
        'sqlContext',
        'bootstrap',
      ])
        key: 'Development diagnostics only.',
    },
    'trials': [
      for (final seed in [42, 43])
        for (final policy in rulePolicies)
          {
            'id': '$policy-$seed',
            'seed': seed,
            'extendedRulePolicy': policy,
            'checkpoint': 'models/$policy-$seed/model.ser',
            'checkpointSha256': 'c' * 64,
            'trainingRows': 20,
            'extendedRuleCount': 0,
            'zeroExtendedRules': true,
            'extendedRules': [],
            for (final field in [
              'architectures',
              'externalDevelopmentDiagnostics',
            ])
              field: {
                for (final architecture in ruleArchitectures)
                  architecture: {
                    'threshold': 0.9999999999999896,
                    'metrics': <String, dynamic>{
                      'total': 10,
                      'tp': 4,
                      'fp': policy == 'BASELINE' ? 2 : 1,
                      'tn': policy == 'BASELINE' ? 3 : 4,
                      'fn': 1,
                      for (final metric in externalReportMetrics) metric: .5,
                    },
                    'confidenceIntervals': <String, dynamic>{
                      for (final metric in externalReportMetrics)
                        metric: {
                          'lower': .4,
                          'upper': .6,
                          'validReplicates': 200,
                          'undefinedReplicates': 0,
                        },
                    },
                  },
              },
          },
    ],
  },
};

class _Reports implements RuleAblationReportsRepository {
  final List<RuleAblationReport> reports;
  Object? error;
  int lists = 0, gets = 0, downloads = 0;
  _Reports(this.reports);
  @override
  Future<List<RuleAblationReport>> listReports() async {
    lists++;
    if (error != null) throw error!;
    return reports;
  }

  @override
  Future<RuleAblationReport> getReport(String id) async {
    gets++;
    if (error != null) throw error!;
    return reports.singleWhere((r) => r.id == id);
  }

  @override
  Future<String> downloadReport(String id) async {
    downloads++;
    if (error != null) throw error!;
    return jsonEncode(reports.singleWhere((r) => r.id == id).json);
  }
}

class _Frozen implements ExternalReportsRepository {
  @override
  Future<List<ExternalReport>> listReports() async => [];
  @override
  Future<ExternalReport> getReport(String id) =>
      throw StateError('Not requested');
  @override
  Future<String> downloadReport(String id) => throw StateError('Not requested');
}

void main() {
  test(
    'validates full seed matrix and rejects malformed or deployed results',
    () {
      expect(RuleAblationReport.fromJson(_fixture()).trials.length, 6);
      for (final corruption in [
        'metric',
        'interval',
        'threshold',
        'duplicate',
        'deployed',
      ]) {
        final data = _fixture();
        final trials = data['trials'] as List;
        final result =
            trials.first['externalDevelopmentDiagnostics']['EXTENDED_ARM']
                as Map;
        switch (corruption) {
          case 'metric':
            (result['metrics'] as Map).remove('accuracy');
          case 'interval':
            (result['confidenceIntervals'] as Map).remove('recall');
          case 'threshold':
            result['threshold'] = 1;
          case 'duplicate':
            trials[1]['extendedRulePolicy'] = 'BASELINE';
          case 'deployed':
            (data['protocol'] as Map)['websiteModelPromoted'] = true;
        }
        expect(
          () => RuleAblationReport.fromJson(data),
          throwsFormatException,
          reason: corruption,
        );
      }
    },
  );

  test('undefined metrics are preserved instead of becoming zero', () {
    final data = _fixture();
    final result =
        (data['trials'] as List)
                .first['externalDevelopmentDiagnostics']['EXTENDED_ARM']
            as Map;
    result['metrics']['precision'] = null;
    result['confidenceIntervals']['precision'] = {
      'lower': null,
      'upper': null,
      'validReplicates': 0,
      'undefinedReplicates': 200,
    };
    final report = RuleAblationReport.fromJson(data);
    expect(report.trials.first.metrics('EXTENDED_ARM')['precision'], isNull);
  });

  test('parses actual completed saved experiment when present', () {
    final file = File(
      '../sqlia_backend/data/experiments/rule-ablation-20261025-1427/ablation-report.json',
    );
    if (!file.existsSync()) return;
    final report = RuleAblationReport.fromJson(
      Map<String, dynamic>.from(jsonDecode(file.readAsStringSync()) as Map),
      reportId: 'rule-ablation-20261025-1427',
    );
    expect(report.trials.length, 6);
    expect(
      report.trials.first.result('EXTENDED_ARM')['threshold'],
      0.9999999999999896,
    );
  });

  testWidgets(
    'existing entry has separated tabs and opens a saved experiment',
    (tester) async {
      final repository = _Reports([RuleAblationReport.fromJson(_fixture())]);
      await tester.pumpWidget(
        MaterialApp(
          home: ExternalReportListScreen(
            extendedOnly: true,
            repository: _Frozen(),
            ablationRepository: repository,
          ),
        ),
      );

      await tester.pumpAndSettle();
      expect(find.text('Frozen evaluation'), findsOneWidget);
      expect(repository.lists, 0);
      await tester.tap(find.text('Rule ablation'));
      await tester.pumpAndSettle();
      expect(repository.lists, 1);
      expect(
        find.textContaining('Research only — not deployed'),
        findsOneWidget,
      );
      await tester.tap(find.text(_id));
      await tester.pumpAndSettle();
      expect(repository.gets, 1);
      expect(
        find.text('Rule ablation · Development experiment'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'small nonzero FPR and near-perfect precision are not rounded to zero or perfect',
    (tester) async {
      final data = _fixture();
      for (final trial in data['trials'] as List) {
        final metrics =
            trial['externalDevelopmentDiagnostics']['PURE_EXTENDED_ARM']['metrics']
                as Map;
        metrics['fpr'] = 0.000020458683688291497;
        metrics['precision'] = 0.999960210090721;
      }
      await tester.binding.setSurfaceSize(const Size(1300, 1200));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          home: RuleAblationReportScreen(
            reportId: _id,
            repository: _Reports([RuleAblationReport.fromJson(data)]),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('0.00205%'), findsWidgets);
      expect(find.text('99.9960%'), findsWidgets);
      expect(find.text('0.00%'), findsNothing);
      expect(find.text('100.00%'), findsNothing);
    },
  );

  testWidgets(
    'both seeds, six metrics, model selection, counts and precise thresholds render',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(1300, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final repository = _Reports([RuleAblationReport.fromJson(_fixture())]);
      await tester.pumpWidget(
        MaterialApp(
          home: RuleAblationReportScreen(reportId: _id, repository: repository),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Accuracy'), findsOneWidget);
      expect(find.text('Baseline / 42'), findsWidgets);
      expect(find.text('Baseline / 43'), findsWidgets);
      await tester.tap(find.byKey(const ValueKey('ablation-architecture')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Hybrid').last);
      await tester.pumpAndSettle();
      await tester.dragUntilVisible(
        find.text('No negative rules / 42: FP change -1; FN change 0'),
        find.byType(Scrollable).first,
        const Offset(0, -450),
      );
      await tester.dragUntilVisible(
        find.text('Baseline / 42 · Details'),
        find.byType(Scrollable).first,
        const Offset(0, -350),
      );
      await tester.tap(find.text('Baseline / 42 · Details'));
      await tester.pumpAndSettle();
      await tester.dragUntilVisible(
        find.textContaining('Validation-selected threshold:'),
        find.byType(Scrollable).first,
        const Offset(0, -250),
      );
      expect(find.textContaining('0.9999999999999896'), findsOneWidget);
      expect(find.text('TP 4 · FP 2 · TN 3 · FN 1'), findsOneWidget);
      expect(find.textContaining('95% CI'), findsWidgets);
      expect(find.text('Train'), findsNothing);
      expect(find.text('Deploy'), findsNothing);
      await tester.tap(find.byTooltip('Refresh experiment'));
      await tester.pumpAndSettle();
      expect(repository.gets, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('incomplete experiments never invent metrics', (tester) async {
    for (final status in ['running', 'failed']) {
      await tester.pumpWidget(
        MaterialApp(
          home: RuleAblationReportScreen(
            key: ValueKey(status),
            reportId: _id,
            repository: _Reports([
              RuleAblationReport.fromJson(_fixture(status: status)),
            ]),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('Experiment status: $status'), findsOneWidget);
      expect(find.byType(DataTable), findsNothing);
    }
  });

  testWidgets('empty and error lists are explicit and retryable', (
    tester,
  ) async {
    final repository = _Reports([]);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: RuleAblationReportList(repository: repository)),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.textContaining('No saved rule ablation experiments'),
      findsOneWidget,
    );
    repository.error = StateError('Invalid saved experiment');
    await tester.tap(find.text('Refresh experiments'));
    await tester.pumpAndSettle();
    expect(find.text('Unable to load rule ablation report'), findsOneWidget);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(repository.lists, 3);
  });

  testWidgets(
    'download fallback displays saved JSON and reports download errors',
    (tester) async {
      final repository = _Reports([RuleAblationReport.fromJson(_fixture())]);
      await tester.pumpWidget(
        MaterialApp(
          home: RuleAblationReportScreen(reportId: _id, repository: repository),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Download experiment JSON'));
      await tester.pumpAndSettle();
      expect(find.text('Rule ablation JSON'), findsOneWidget);
      expect(find.text('Copy JSON'), findsOneWidget);
      expect(repository.downloads, 1);
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();
      repository.error = StateError('Download failed');
      await tester.tap(find.byTooltip('Download experiment JSON'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Unable to download experiment:'),
        findsOneWidget,
      );
    },
  );

  test(
    'HTTP repository uses only GET and validates raw downloads without runId',
    () async {
      final client = ApiClient();
      final adapter = _Adapter();
      client.dio.httpClientAdapter = adapter;
      final repository = RuleAblationReportService(client);
      expect((await repository.listReports()).single.id, _id);
      expect((await repository.getReport(_id)).trials.length, 6);
      expect(
        jsonDecode(await repository.downloadReport(_id))['schemaVersion'],
        'sqlia-rule-ablation-v1',
      );
      expect(adapter.paths, [
        '/api/experiments/rule-ablation/reports',
        '/api/experiments/rule-ablation/reports/$_id',
        '/api/experiments/rule-ablation/reports/$_id/download',
      ]);
      adapter.invalid = true;
      await expectLater(repository.downloadReport(_id), throwsFormatException);
    },
  );
}

class _Adapter implements HttpClientAdapter {
  final paths = <String>[];
  bool invalid = false;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    expect(options.method, 'GET');
    paths.add(options.path);
    final data = _fixture();
    if (options.path.endsWith('/download')) data.remove('runId');
    if (invalid) data.remove('trials');
    return ResponseBody.fromString(
      jsonEncode(
        options.path.endsWith('/reports')
            ? {
                'reports': [data],
              }
            : data,
      ),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
