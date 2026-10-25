import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlia_frontend/core/detection_input.dart';
import 'package:sqlia_frontend/core/api_client.dart';
import 'package:sqlia_frontend/models/architecture_prediction.dart';
import 'package:sqlia_frontend/providers/detection_provider.dart';
import 'package:sqlia_frontend/screens/architecture_query_screen.dart';
import 'package:sqlia_frontend/screens/detect_screen.dart';
import 'package:sqlia_frontend/screens/ratio_ml_screen.dart';
import 'package:sqlia_frontend/services/architecture_service.dart';

void main() {
  test('mixed input validation and exact length boundaries', () {
    for (final value in [
      'SELECT 1',
      "' OR 1=1 --",
      '47209',
      'example@example.com',
      'ordinary words',
      List.filled(4000, 'a').join(),
    ]) {
      expect(validateDetectionInput(value), isNull);
    }
    for (final value in ['', ' \n\t', List.filled(4001, 'a').join()]) {
      expect(validateDetectionInput(value), isNotNull);
    }
  });

  final screens = <String, (Widget, String)>{
    'single detector': (const DetectScreen(), 'Check for SQL injection'),
    'all architectures': (
      const ArchitectureQueryScreen(),
      'Compare all five architectures',
    ),
    'extended architectures': (
      const ArchitectureQueryScreen(extendedOnly: true),
      'Compare both Extended ARM methods',
    ),
    'online classifier': (const RatioMlScreen(), 'Classify input'),
  };
  for (final entry in screens.entries) {
    testWidgets('${entry.key} rejects blank and oversize input locally', (
      tester,
    ) async {
      await tester.binding.setSurfaceSize(const Size(1200, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        ProviderScope(child: MaterialApp(home: entry.value.$1)),
      );
      expect(find.text(detectionInputLabel), findsOneWidget);
      expect(find.text(detectionDisclaimer), findsOneWidget);
      final button = find.text(entry.value.$2);
      await tester.ensureVisible(button);
      await tester.tap(button);
      await tester.pump();
      expect(find.text(validateDetectionInput('')!), findsOneWidget);
      await tester.enterText(
        find.byType(TextField).first,
        List.filled(4001, 'a').join(),
      );
      await tester.ensureVisible(button);
      await tester.tap(button);
      await tester.pump();
      expect(find.text(validateDetectionInput('')!), findsOneWidget);
    });
  }

  testWidgets('mixed input reaches prediction without trimming the original', (
    tester,
  ) async {
    final service = _CapturingArchitectureService();
    await tester.binding.setSurfaceSize(const Size(1200, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [architectureServiceProvider.overrideWithValue(service)],
        child: const MaterialApp(home: ArchitectureQueryScreen()),
      ),
    );
    for (final input in ['  ordinary words  ', '47209', "' OR 1=1 --"]) {
      await tester.enterText(find.byType(TextField), input);
      await tester.tap(find.text('Compare all five architectures'));
      await tester.pumpAndSettle();
      expect(service.input, input);
    }
  });
}

class _CapturingArchitectureService extends ArchitectureService {
  _CapturingArchitectureService() : super(ApiClient());
  String? input;

  @override
  Future<List<ArchitecturePrediction>> predictAll(
    String sql, {
    bool extendedOnly = false,
  }) async {
    input = sql;
    return [];
  }
}
