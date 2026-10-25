import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlia_frontend/models/external_report.dart';
import 'package:sqlia_frontend/screens/dashboard_screen.dart';
import 'package:sqlia_frontend/screens/external_report_screen.dart';
import 'package:sqlia_frontend/services/external_report_service.dart';

void main() {
  test('complete reports reject missing required metric intervals', () {
    final report = _completeReport();
    ((report['models'] as List).first as Map<String, dynamic>).remove(
      'confidenceIntervals',
    );
    expect(() => ExternalReport.fromJson(report), throwsFormatException);
  });

  testWidgets('dashboard opens external report mode selection and saved list', (
    tester,
  ) async {
    final repository = _FakeReports([]);
    await tester.binding.setSurfaceSize(const Size(1200, 1100));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(home: DashboardScreen(externalReportsRepository: repository)),
    );

    final card = find.text('6. External generalization');
    await tester.tap(card);
    await tester.pumpAndSettle();
    expect(find.text('A. All architectures'), findsOneWidget);
    expect(find.text('B. Two Extended ARM methods'), findsOneWidget);

    await tester.tap(find.text('A. All architectures'));
    await tester.pumpAndSettle();
    expect(find.text('No saved external reports yet'), findsOneWidget);
    expect(repository.listCalls, 1);
    await tester.tap(find.byTooltip('Refresh saved reports'));
    await tester.pumpAndSettle();
    expect(repository.listCalls, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'detail renders saved dataset, metrics, intervals and limitations',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(1200, 1200));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          home: ExternalReportDetailScreen(
            reportId: 'generalization-fixture',
            extendedOnly: false,
            repository: _FakeReports([
              ExternalReport.fromJson(_completeReport()),
            ]),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.text('Retrospective / external overlap-filtered'),
        findsOneWidget,
      );
      expect(find.text('Original rows'), findsOneWidget);
      expect(find.text('10'), findsOneWidget);
      await _scrollToText(tester, 'Kaggle source; independence unverified.');
      expect(
        find.textContaining('Kaggle source; independence unverified.'),
        findsOneWidget,
      );
      await _scrollToText(tester, 'Pure Extended ARM');
      expect(find.text('Pure Extended ARM'), findsWidgets);
      await _scrollToText(tester, 'Average precision (AP)');
      expect(find.text('Average precision (AP)'), findsWidgets);
      await _scrollToText(tester, 'Selected-model metric comparison');
      expect(find.text('Selected-model metric comparison'), findsOneWidget);
      await _scrollToText(tester, '0.999997123456789');
      expect(find.text('0.999997123456789'), findsOneWidget);
      await _scrollToText(
        tester,
        'Historical test comparison unavailable for this frozen report.',
      );
      expect(
        find.text(
          'Historical test comparison unavailable for this frozen report.',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('95% CI'), findsWidgets);
      expect(find.text('Train'), findsNothing);
      expect(find.text('Evaluate'), findsNothing);
    },
  );

  testWidgets('running, failed and blocked reports do not invent metrics', (
    tester,
  ) async {
    for (final status in ['running', 'failed', 'blocked']) {
      await tester.pumpWidget(
        MaterialApp(
          home: ExternalReportDetailScreen(
            reportId: 'generalization-$status',
            extendedOnly: false,
            repository: _FakeReports([
              ExternalReport.fromJson(_incompleteReport(status)),
            ]),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining(_statusDescription(status)), findsOneWidget);
      expect(find.text('Accuracy'), findsNothing);
      expect(find.text('Confusion matrix'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });

  testWidgets('focused metric chart contains only Pure and Hybrid models', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1200, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        home: ExternalReportDetailScreen(
          reportId: 'generalization-fixture',
          extendedOnly: true,
          repository: _FakeReports([
            ExternalReport.fromJson(_completeReport()),
          ]),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _scrollToText(tester, 'Selected-model metric comparison');

    expect(find.text('Accuracy'), findsWidgets);
    expect(find.byType(LinearProgressIndicator), findsNWidgets(12));
  });

  testWidgets('report read errors are explicit and retryable', (tester) async {
    final repository = _FakeReports.error('Invalid external report schema');
    await tester.pumpWidget(
      MaterialApp(
        home: ExternalReportListScreen(
          extendedOnly: false,
          repository: repository,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Unable to load saved external reports'), findsOneWidget);
    expect(
      find.textContaining('Invalid external report schema'),
      findsOneWidget,
    );
    expect(find.text('Try again'), findsOneWidget);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(repository.listCalls, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('detail refresh stays synchronous and reloads the saved report', (
    tester,
  ) async {
    final repository = _FakeReports([
      ExternalReport.fromJson(_completeReport()),
    ]);
    await tester.pumpWidget(
      MaterialApp(
        home: ExternalReportDetailScreen(
          reportId: 'generalization-fixture',
          extendedOnly: false,
          repository: repository,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Refresh report'));
    await tester.pumpAndSettle();
    expect(repository.getCalls, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('non-web download fallback presents selectable saved JSON', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: ExternalReportDetailScreen(
          reportId: 'generalization-fixture',
          extendedOnly: false,
          repository: _FakeReports([
            ExternalReport.fromJson(_completeReport()),
          ]),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Download report JSON'));
    await tester.pump();

    expect(find.text('External report JSON'), findsOneWidget);
    expect(find.byType(SelectableText), findsWidgets);
    expect(find.textContaining('"sqlia-external-report-v1"'), findsOneWidget);
  });
}

Future<void> _scrollToText(WidgetTester tester, String text) async {
  final target = find.text(text);
  final scrollable = find.byType(ListView).last;
  for (var attempt = 0; attempt < 30; attempt++) {
    if (target.evaluate().isNotEmpty) {
      await tester.ensureVisible(target.first);
      await tester.pumpAndSettle();
      return;
    }
    await tester.drag(scrollable, const Offset(0, -400));
    await tester.pumpAndSettle();
  }
  fail('Could not find "$text" while scrolling the report.');
}

class _FakeReports implements ExternalReportsRepository {
  final List<ExternalReport> reports;
  String? errorMessage;
  int listCalls = 0;
  int getCalls = 0;

  _FakeReports(this.reports) : errorMessage = null;

  _FakeReports.error(this.errorMessage) : reports = const [];

  @override
  Future<List<ExternalReport>> listReports() async {
    listCalls++;
    final error = errorMessage;
    errorMessage = null;
    if (error != null) throw StateError(error);
    return reports;
  }

  @override
  Future<ExternalReport> getReport(String id) async {
    getCalls++;
    return reports.firstWhere((report) => report.id == id);
  }

  @override
  Future<String> downloadReport(String id) async =>
      const JsonEncoder.withIndent('  ').convert(_completeReport());
}

String _statusDescription(String status) => switch (status) {
  'running' => 'This saved report is still running. Completed evaluation metrics are not available yet.',
  'failed' =>
    'This report failed. No completed evaluation metrics are presented.',
  _ => 'This evaluation was blocked. No completed evaluation metrics are presented.',
};

Map<String, dynamic> _incompleteReport(String status) => {
  'schemaVersion': 'sqlia-external-report-v1',
  'status': status,
  'reportType': 'external_generalization',
  'runId': 'generalization-$status',
  'createdAt': '2026-10-25T00:00:00Z',
  'evaluationMode': 'frozen_external_checkpoint',
  'blindTestEligible': false,
  'limitations': ['External overlap and independence limitations apply.'],
  'policy': {'retrained': false, 'updated': false, 'thresholdsRetuned': false},
  'dataset': <String, dynamic>{},
  'models': <dynamic>[],
};

Map<String, dynamic> _completeReport() => {
  'schemaVersion': 'sqlia-external-report-v1',
  'status': 'complete',
  'reportType': 'external_generalization',
  'runId': 'generalization-fixture',
  'createdAt': '2026-10-25T00:00:00Z',
  'completedAt': '2026-10-25T00:01:00Z',
  'evaluationMode': 'frozen_external_checkpoint',
  'blindTestEligible': false,
  'limitations': ['Kaggle source; independence unverified.'],
  'provenance': {'datasetOrigin': 'Kaggle'},
  'policy': {'retrained': false, 'updated': false, 'thresholdsRetuned': false},
  'dataset': {
    'source': 'clean_sql_dataset',
    'fileSha256': 'dataset-hash',
    'originalRows': 10,
    'evaluatedRows': 8,
    'benignCount': 4,
    'maliciousCount': 4,
    'excludedRows': 2,
    'exclusionCounts': {'overlap': 2},
    'auditReportSha256': 'audit-hash',
  },
  'models': [
    _model('PURE_EXTENDED_ARM'),
    _model('EXTENDED_ARM'),
    _model('ARM_PROFILE'),
    _model('RATIO_INCREMENTAL_ML'),
    _model('FULL_FLOW'),
  ],
};

Map<String, dynamic> _model(String architecture) {
  const metricNames = [
    'accuracy',
    'precision',
    'recall',
    'f1',
    'fpr',
    'averagePrecision',
  ];
  return {
    'architecture': architecture,
    'checkpointSha256': 'checkpoint-hash',
    'threshold': architecture == 'EXTENDED_ARM' ? 0.999997123456789 : 0.5,
    'trainingPolicy': 'baseline_same_training',
    'metrics': {
      'total': 8,
      'tp': 3,
      'fp': 1,
      'tn': 3,
      'fn': 1,
      for (final metric in metricNames) metric: 0.75,
    },
    'confidenceIntervals': {
      for (final metric in metricNames)
        metric: {
          'lower': 0.5,
          'upper': 1.0,
          'validReplicates': 100,
          'undefinedReplicates': 0,
        },
    },
  };
}
