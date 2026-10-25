import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:sqlia_frontend/core/api_client.dart';
import 'package:sqlia_frontend/models/architecture_performance.dart';
import 'package:sqlia_frontend/providers/detection_provider.dart';
import 'package:sqlia_frontend/screens/performance_screen.dart';
import 'package:sqlia_frontend/services/architecture_service.dart';

Map<String, dynamic> fixture() => {
  'architecture': 'FULL_FLOW',
  'total': 2,
  'accuracy': 0.5,
  'precision': 0.5,
  'recall': 1.0,
  'f1': 2 / 3,
  'durationMs': 3,
  'tp': 1,
  'fp': 1,
  'tn': 0,
  'fn': 0,
  'fpr': 1.0,
  'threshold': 0.7,
  'trainingSamples': 2000,
  'evaluationMode': 'interactive_website_snapshot',
  'bySource': {
    'fixture-source': {'total': 2, 'f1': 2 / 3, 'fpr': 1.0},
  },
  'byInputType': {
    'numeric': {'total': 2, 'f1': 2 / 3, 'fpr': 1.0},
  },
};

void main() {
  testWidgets(
    'chart labels follow architecture IDs rather than numeric positions',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(1400, 2000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            architectureServiceProvider.overrideWithValue(
              _ComparisonService(
                architectures: ['PURE_EXTENDED_ARM', 'EXTENDED_ARM'],
              ),
            ),
          ],
          child: const MaterialApp(home: PerformanceScreen(extendedOnly: true)),
        ),
      );
      await tester.tap(find.text('Compare performance'));
      await tester.pumpAndSettle();
      expect(find.text('Pure ARM'), findsOneWidget);
      expect(find.text('Hybrid'), findsOneWidget);
      final chart = tester.widget<BarChart>(find.byType(BarChart));
      expect(chart.data.titlesData.topTitles.sideTitles.showTitles, isFalse);
      expect(chart.data.titlesData.rightTitles.sideTitles.showTitles, isFalse);
      expect(chart.data.barGroups.map((g) => g.x), [0, 1]);
      expect(tester.takeException(), isNull);
    },
  );

  test('parses diagnostics and rejects old incomplete benchmark output', () {
    final result = ArchitecturePerformance.fromJson(fixture());
    expect(result.fp, 1);
    expect(result.trainingSamples, 2000);
    expect(result.threshold, 0.7);
    expect(result.datasetFingerprint, isNull);
    expect(result.validationF1, isNull);
    final old = fixture()..remove('threshold');
    expect(
      () => ArchitecturePerformance.fromJson(old),
      throwsA(isA<TypeError>()),
    );
  });

  testWidgets('uses current website models without an experiment directory', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1400, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final service = _ComparisonService();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [architectureServiceProvider.overrideWithValue(service)],
        child: const MaterialApp(home: PerformanceScreen()),
      ),
    );
    expect(
      find.textContaining('Results reflect the current interactive models'),
      findsOneWidget,
    );
    expect(
      find.text('Saved experiment directory (backend-local)'),
      findsNothing,
    );
    expect(find.text('Test dataset'), findsOneWidget);
    await tester.tap(find.text('Compare performance'));
    await tester.pumpAndSettle();
    expect(service.path, 'data/processed/test.csv');
    expect(service.samples, 5345);
    await tester.scrollUntilVisible(
      find.text('False positive rate: 100.0%'),
      400,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('TP 1 | FP 1 | TN 0 | FN 0'), findsOneWidget);
    expect(find.text('Training updates: 2000'), findsOneWidget);
    expect(find.textContaining('interactive_website_snapshot'), findsOneWidget);
    expect(find.textContaining('Validation F1:'), findsNothing);
  });
}

class _ComparisonService extends ArchitectureService {
  final List<String> architectures;
  _ComparisonService({this.architectures = const ['FULL_FLOW']})
    : super(ApiClient());
  String? path;
  int? samples;

  @override
  Future<List<ArchitecturePerformance>> compare({
    required String datasetPath,
    required int sampleSize,
    bool extendedOnly = false,
  }) async {
    path = datasetPath;
    samples = sampleSize;
    return [
      for (final architecture in architectures)
        ArchitecturePerformance.fromJson({
          ...fixture(),
          'architecture': architecture,
        }),
    ];
  }
}
