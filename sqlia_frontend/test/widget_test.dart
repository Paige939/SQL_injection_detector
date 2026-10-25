// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:sqlia_frontend/screens/architecture_intro_screen.dart';

import 'package:sqlia_frontend/main.dart';

void main() {
  testWidgets('All five architecture diagrams load without SVG errors', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1200, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const MaterialApp(home: ArchitectureIntroScreen()));
    final buttons = find.byType(FilledButton);
    expect(buttons, findsNWidgets(5));
    for (var index = 0; index < 5; index++) {
      await tester.tap(buttons.at(index));
      await tester.pumpAndSettle();
      expect(find.byType(SvgPicture), findsOneWidget);
      expect(find.textContaining('Image not found'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pageBack();
      await tester.pumpAndSettle();
    }
  });
  testWidgets('Dashboard displays its title', (WidgetTester tester) async {
    await tester.pumpWidget(const SqliaApp());

    expect(find.text('Extended ARM SQL Injection Defense'), findsOneWidget);
    expect(find.text('Deployment and research reports'), findsNothing);
    expect(find.text('2. Compare one input'), findsOneWidget);
    expect(find.text('5. Replay test data'), findsOneWidget);
  });
}
