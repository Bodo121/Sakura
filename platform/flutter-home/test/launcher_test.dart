import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sakura_home/main.dart';
import 'package:sakura_home/platform.dart';

class FakeHost extends LauncherHost {
  final calls = <String>[];
  @override
  Future<Map<dynamic, dynamic>> load() async => {
    'apps': [
      {'id': 'settings', 'label': 'Settings'},
      {'id': 'camera', 'label': 'Camera'},
    ],
    'favorites': ['camera'],
    'dock': ['settings'],
    'initialized': true,
    'motion': false,
    'glass': false,
  };
  @override
  Future<Uint8List?> icon(String id) async => null;
  @override
  Future<void> save(Map<String, dynamic> value) async {}
  @override
  Future<List<int>> rank(List<LaunchApp> apps, String query) async => [
    for (var i = 0; i < apps.length; i++)
      if (apps[i].label.toLowerCase().contains(query.toLowerCase())) i,
  ];
  @override
  Future<void> launch(String id) async {
    calls.add(id);
  }
}

void main() {
  testWidgets('Library search, empty state, app launch and drawer close', (
    tester,
  ) async {
    final host = FakeHost();
    await tester.pumpWidget(SakuraHome(host: host));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('home-camera')), findsOneWidget);
    await tester.tap(find.byKey(const Key('open-drawer')));
    await tester.pumpAndSettle();
    expect(find.text('Your library'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('app-search')), 'Settings');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('library-settings')), findsOneWidget);
    expect(find.byKey(const ValueKey('library-camera')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('library-settings')));
    await tester.pumpAndSettle();
    expect(host.calls, ['settings']);
    await tester.enterText(find.byKey(const Key('app-search')), 'zzzz');
    await tester.pumpAndSettle();
    expect(find.text('No apps found'), findsOneWidget);
    await tester.tap(find.byKey(const Key('close-drawer')));
    await tester.pumpAndSettle();
    expect(find.text('Your library'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('Material 3 customization persists appearance controls', (
    tester,
  ) async {
    await tester.pumpWidget(SakuraHome(host: FakeHost()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('customize')));
    await tester.pumpAndSettle();
    expect(find.text('Sakura Home · Material 3'), findsOneWidget);
    expect(find.byType(SegmentedButton<String>), findsOneWidget);
    await tester.tap(find.text('Dark'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
