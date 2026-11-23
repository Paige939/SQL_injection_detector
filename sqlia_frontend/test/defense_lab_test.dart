import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlia_frontend/core/api_client.dart';
import 'package:sqlia_frontend/models/defense_result.dart';
import 'package:sqlia_frontend/screens/dashboard_screen.dart';
import 'package:sqlia_frontend/screens/defense_lab_screen.dart';
import 'package:sqlia_frontend/services/defense_service.dart';

Map<String, dynamic> fixture({
  String status = 'rejected',
  String action = 'block',
  List<Map<String, dynamic>> products = const [],
}) => {
  'requestId': 'fixture-request',
  'timestamp': '2026-10-31T12:00:00Z',
  'mode': 'ENFORCE',
  'architecture': 'EXTENDED_ARM',
  'algorithm': 'SGD',
  'modelVersion': 'fixture-version',
  'status': status,
  'recommendedAction': action,
  'effectiveAction': action,
  'reason': switch (action) {
    'review' => 'MANUAL_REVIEW_REQUIRED',
    'allow' => 'USER_ALLOWED_REVIEW',
    'block' => 'USER_BLOCKED_REVIEW',
    _ => 'DETECTION_THRESHOLD_EXCEEDED',
  },
  'errorCode': status == 'failed' ? 'DETECTOR_UNAVAILABLE' : null,
  'inputLength': 8,
  'businessQueryAttempted': status == 'complete',
  'businessQueryCompleted': status == 'complete',
  'score': 0.9,
  'threshold': 0.5,
  'detectionDurationMs': 1,
  'queryDurationMs': 0,
  'totalDurationMs': 2,
  'matchedRules': [],
  'products': products,
};

void main() {
  for (final code in [403, 503]) {
    test(
      'HTTP $code preserves structured rejection/error instead of success fallback',
      () async {
        final client = ApiClient();
        client.dio.httpClientAdapter = _Adapter(code);
        final result = await DefenseService(client).search(
          keyword: 'Keyboard',
          architecture: 'EXTENDED_ARM',
          algorithm: 'SGD',
        );
        expect(result.businessQueryAttempted, isFalse);
        expect(result.status, code == 403 ? 'rejected' : 'failed');
        if (code == 503) expect(result.errorCode, 'DETECTOR_UNAVAILABLE');
        client.dio.close();
      },
    );
  }

  testWidgets('dashboard defense entry retains A and B selection', (
    tester,
  ) async {
    await tester.pumpWidget(const MaterialApp(home: DashboardScreen()));
    await tester.scrollUntilVisible(find.text('7. Defense laboratory'), 300);
    await tester.tap(find.text('7. Defense laboratory'));
    await tester.pumpAndSettle();
    expect(find.text('A. All architectures'), findsOneWidget);
    expect(find.text('B. Two Extended ARM methods'), findsOneWidget);
  });

  for (final extendedOnly in [false, true]) {
    testWidgets(
      'mode $extendedOnly exposes correct architectures and Extended algorithms',
      (tester) async {
        await tester.binding.setSurfaceSize(const Size(1400, 1800));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        final service = _Service();
        await tester.pumpWidget(
          ProviderScope(
            overrides: [defenseServiceProvider.overrideWithValue(service)],
            child: MaterialApp(
              home: DefenseLabScreen(extendedOnly: extendedOnly),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final selector = tester.widget<DropdownButton<String>>(
          find.byType(DropdownButton<String>).first,
        );
        expect(selector.items!.length, extendedOnly ? 2 : 5);
        await tester.tap(find.byType(DropdownButtonFormField<String>).first);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Extended ARM + Incremental ML').last);
        await tester.pumpAndSettle();
        await tester.tap(find.byType(DropdownButtonFormField<String>).last);
        await tester.pumpAndSettle();
        await tester.tap(find.text('SGD / Incremental SVM').last);
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextField).first, 'Keyboard');
        await tester.tap(find.text('Search through defense gate'));
        await tester.pumpAndSettle();
        expect(service.architecture, 'EXTENDED_ARM');
        expect(service.algorithm, 'SGD');
        expect(find.text('This search was blocked.'), findsOneWidget);
        expect(
          find.text(
            'The product lookup was not run. No product results were returned.',
          ),
          findsOneWidget,
        );
        expect(tester.takeException(), isNull);
        await tester.tap(find.text('Back to search'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Test & algorithms'));
        await tester.pumpAndSettle();
        final compare = find.text(
          extendedOnly
              ? 'Compare both methods and three algorithms'
              : 'Compare five architectures and three algorithms',
        );
        await tester.ensureVisible(compare);
        await tester.tap(compare);
        await tester.pumpAndSettle();
        expect(service.extendedOnly, extendedOnly);
        expect(service.sampleSize, 1000);
      },
    );
  }

  testWidgets('review allow returns matching products', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1400, 1800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final service = _ReviewService();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [defenseServiceProvider.overrideWithValue(service)],
        child: const MaterialApp(home: DefenseLabScreen(extendedOnly: true)),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, '10000');
    await tester.tap(find.text('Search through defense gate'));
    await tester.pumpAndSettle();
    expect(find.text('Allow search'), findsOneWidget);
    expect(find.text('Block'), findsOneWidget);
    expect(find.text('Product lookup not run'), findsOneWidget);

    await tester.tap(find.text('Allow search'));
    await tester.pumpAndSettle();
    expect(service.decision, 'allow');
    expect(find.text('Product lookup completed'), findsOneWidget);
    expect(find.text('Notebook'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('predicted benign search returns products immediately', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1400, 1800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          defenseServiceProvider.overrideWithValue(_AllowedService()),
        ],
        child: const MaterialApp(home: DefenseLabScreen(extendedOnly: true)),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, 'Keyboard');
    await tester.tap(find.text('Search through defense gate'));
    await tester.pumpAndSettle();
    expect(find.text('Predicted benign — search allowed'), findsOneWidget);
    expect(find.text('Keyboard').last, findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('review block opens a page without product results', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1400, 1800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final service = _ReviewService();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [defenseServiceProvider.overrideWithValue(service)],
        child: const MaterialApp(home: DefenseLabScreen(extendedOnly: true)),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, '10000');
    await tester.tap(find.text('Search through defense gate'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Block'));
    await tester.pumpAndSettle();
    expect(service.decision, 'block');
    expect(find.text('This search was blocked.'), findsOneWidget);
    expect(find.text('Notebook'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

class _Service extends DefenseService {
  _Service() : super(ApiClient());
  String? architecture, algorithm;
  bool? extendedOnly;
  int? sampleSize;
  @override
  Future<Map<String, dynamic>> status() async => {
    'mode': 'ENFORCE',
    'reviewPolicy': 'user_decision',
    'trainingSamples': 2000,
    'modelVersion': 'fixture-version',
  };
  @override
  Future<Map<String, dynamic>> audit() async => {
    'records': [],
    'summary': {'groups': [], 'note': 'Fixture audit'},
  };
  @override
  Future<Map<String, dynamic>> latestEvaluation() async => {
    'status': 'missing',
  };
  @override
  Future<DefenseResult> search({
    required String keyword,
    required String architecture,
    required String algorithm,
  }) async {
    this.architecture = architecture;
    this.algorithm = algorithm;
    return DefenseResult.fromJson(fixture());
  }

  @override
  Future<DefenseResult> resolveReview({
    required String requestId,
    required String decision,
  }) async => DefenseResult.fromJson(
    fixture(
      status: decision == 'allow' ? 'complete' : 'rejected',
      action: decision,
      products: decision == 'allow'
          ? [
              {
                'id': 1,
                'name': 'Notebook',
                'category': 'Demo accessories',
                'priceCents': 10000,
              },
            ]
          : const [],
    ),
  );

  @override
  Future<Map<String, dynamic>> evaluate({
    required bool extendedOnly,
    required int sampleSize,
  }) async {
    this.extendedOnly = extendedOnly;
    this.sampleSize = sampleSize;
    return {'status': 'missing'};
  }
}

class _ReviewService extends _Service {
  String? decision;

  @override
  Future<DefenseResult> search({
    required String keyword,
    required String architecture,
    required String algorithm,
  }) async => DefenseResult.fromJson(
    fixture(status: 'review_pending', action: 'review'),
  );

  @override
  Future<DefenseResult> resolveReview({
    required String requestId,
    required String decision,
  }) async {
    this.decision = decision;
    return super.resolveReview(requestId: requestId, decision: decision);
  }
}

class _AllowedService extends _Service {
  @override
  Future<DefenseResult> search({
    required String keyword,
    required String architecture,
    required String algorithm,
  }) async => DefenseResult.fromJson(
    fixture(
      status: 'complete',
      action: 'allow',
      products: [
        {
          'id': 2,
          'name': 'Keyboard',
          'category': 'Demo accessories',
          'priceCents': 20000,
        },
      ],
    ),
  );
}

class _Adapter implements HttpClientAdapter {
  final int status;
  _Adapter(this.status);
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    jsonEncode(fixture(status: status == 403 ? 'rejected' : 'failed')),
    status,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
  @override
  void close({bool force = false}) {}
}
