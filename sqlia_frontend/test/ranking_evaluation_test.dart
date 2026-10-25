import 'dart:convert';
import 'dart:io';

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlia_frontend/models/external_report.dart';
import 'package:sqlia_frontend/models/ranking_evaluation.dart';
import 'package:sqlia_frontend/models/rule_ablation_report.dart';
import 'package:sqlia_frontend/widgets/ranking_evaluation_panel.dart';

Map<String, dynamic> _result() => {
  'threshold': .8,
  'metrics': <String, dynamic>{
    'total': 2,
    'tp': 1,
    'fp': 0,
    'tn': 1,
    'fn': 0,
    'precision': 1,
    'recall': 1,
    'fpr': 0,
    'rocAuc': 1,
    'averagePrecision': 1,
  },
  'confidenceIntervals': <String, dynamic>{
    for (final key in ['rocAuc', 'averagePrecision'])
      key: {
        'lower': 1,
        'upper': 1,
        'validReplicates': 100,
        'undefinedReplicates': 100,
      },
  },
  'rankingCurves': {
    'totalScoreGroups': 2,
    'displayPolicy': 'All ties retained. Display only; no retuning.',
    'points': [
      <String, dynamic>{
        'threshold': null,
        'fpr': 0,
        'recall': 0,
        'precision': 1,
      },
      <String, dynamic>{'threshold': .9, 'fpr': 0, 'recall': 1, 'precision': 1},
      <String, dynamic>{
        'threshold': .1,
        'fpr': 1,
        'recall': 1,
        'precision': .5,
      },
    ],
    'operatingPoint': {'threshold': .8, 'fpr': 0, 'recall': 1, 'precision': 1},
  },
};

void main() {
  test('accepts ranking extension and historical absence', () {
    validateRankingEvaluation(_result());
    validateRankingEvaluation({
      'metrics': {'tp': 1, 'fp': 0, 'tn': 1, 'fn': 0},
      'confidenceIntervals': {},
    });
  });

  test('rejects partial, invalid, nonmonotonic and mismatched extensions', () {
    for (final mutate in <void Function(Map<String, dynamic>)>[
      (r) => (r['metrics'] as Map).remove('rocAuc'),
      (r) => (r['metrics'] as Map)['rocAuc'] = double.nan,
      (r) => (r['metrics'] as Map)['rocAuc'] = null,
      (r) => (r['confidenceIntervals'] as Map).remove('rocAuc'),
      (r) =>
          ((r['rankingCurves'] as Map)['operatingPoint'] as Map)['threshold'] =
              .5,
      (r) =>
          (((r['rankingCurves'] as Map)['points'] as List).last as Map)['fpr'] =
              .5,
      (r) =>
          (((r['rankingCurves'] as Map)['points'] as List)[1]
                  as Map)['threshold'] =
              null,
    ]) {
      final result = _result();
      mutate(result);
      expect(() => validateRankingEvaluation(result), throwsFormatException);
    }
  });

  testWidgets('renders both curves and saved operating point', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1000, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: RankingEvaluationPanel(result: _result()),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(LineChart), findsNWidgets(2));
    expect(find.textContaining('ROC-AUC: 1.0000'), findsOneWidget);
    expect(find.text('Saved threshold: 0.8'), findsOneWidget);
    final charts = tester
        .widgetList<LineChart>(find.byType(LineChart))
        .toList();
    expect(
      charts.first.data.lineBarsData.last.spots.single,
      const FlSpot(0, 1),
    );
    expect(charts.last.data.lineBarsData.last.spots.single, const FlSpot(1, 1));
    expect(tester.takeException(), isNull);
  });

  testWidgets('historical data is unavailable rather than zero', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: RankingEvaluationPanel(
          result: {'metrics': {}, 'confidenceIntervals': {}},
        ),
      ),
    );
    expect(
      find.textContaining('unavailable in this historical report'),
      findsOneWidget,
    );
    expect(find.byType(LineChart), findsNothing);
  });

  testWidgets('single-class AUC stays undefined while PR remains available', (
    tester,
  ) async {
    final result = _result();
    final metrics = result['metrics'] as Map;
    metrics['rocAuc'] = null;
    metrics['tp'] = 2;
    metrics['tn'] = 0;
    metrics['fpr'] = null;
    final intervals = result['confidenceIntervals'] as Map;
    intervals['rocAuc'] = {
      'lower': null,
      'upper': null,
      'validReplicates': 0,
      'undefinedReplicates': 200,
    };
    final curves = result['rankingCurves'] as Map;
    for (final point in curves['points'] as List) {
      (point as Map)['fpr'] = null;
      point['precision'] = 1;
    }
    ((curves['points'] as List)[1] as Map)['recall'] = .5;
    curves['operatingPoint'] = {
      'threshold': .1,
      'fpr': null,
      'recall': 1,
      'precision': 1,
    };
    result['threshold'] = .1;
    validateRankingEvaluation(result);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: RankingEvaluationPanel(result: result),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('ROC-AUC: Undefined'), findsOneWidget);
    expect(find.textContaining('ROC curve undefined'), findsOneWidget);
    expect(find.byType(LineChart), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  final root = Platform.environment['SQLIA_RANKING_REPORT_ROOT'];
  test(
    'saved ranking reports parse if supplied through environment',
    () {
      if (root == null) {
        throw StateError('Report root is required for this integration test.');
      }
      final external = jsonDecode(
        File('$root/generalization-ranking-20261025-1554/external-report.json')
            .readAsStringSync(),
      ) as Map<String, dynamic>;
      final ablation = jsonDecode(
        File('$root/rule-ablation-ranking-20261025-1554/ablation-report.json')
            .readAsStringSync(),
      ) as Map<String, dynamic>;
      expect(ExternalReport.fromJson(external).models, hasLength(5));
      expect(RuleAblationReport.fromJson(ablation).trials, hasLength(6));
    },
    skip: root == null
        ? 'Set SQLIA_RANKING_REPORT_ROOT for saved-report integration validation.'
        : false,
  );
}
