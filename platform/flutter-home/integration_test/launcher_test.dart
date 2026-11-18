import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:sakura_home/main.dart' as app;
import 'package:sakura_home/platform.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'Android catalog, Rust search, native glass, drawer gestures and launching',
    (tester) async {
      app.main();
      for (
        var i = 0;
        i < 80 && find.byKey(const Key('open-drawer')).evaluate().isEmpty;
        i++
      ) {
        await tester.pump(const Duration(milliseconds: 250));
      }
      final host = LauncherHost();
      final data = await host.load();
      expect(data['rust'], true);
      final apps = (data['apps'] as List)
          .map((a) => LaunchApp.fromMap(a as Map))
          .toList();
      expect(apps, isNotEmpty);
      final settings = apps.firstWhere(
        (a) => a.label.toLowerCase() == 'settings',
      );
      final rank = await host.rank(apps, 'Settings');
      expect(rank.map((i) => apps[i].label), contains('Settings'));
      await tester.pumpAndSettle();
      await LauncherHost.channel.invokeMethod('capture', 'home');
      await tester.tap(find.byKey(const Key('open-drawer')));
      await tester.pumpAndSettle();
      expect(find.text('Your library'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('app-search')), 'Settings');
      await tester.pumpAndSettle();
      expect(find.byKey(ValueKey('library-${settings.id}')), findsOneWidget);
      await LauncherHost.channel.invokeMethod('capture', 'search');
      await tester.enterText(
        find.byKey(const Key('app-search')),
        'zzzz-not-an-app',
      );
      await tester.pumpAndSettle();
      expect(find.text('No apps found'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('app-search')), '');
      await tester.pumpAndSettle();
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      FocusManager.instance.primaryFocus?.unfocus();
      await tester.pumpAndSettle();
      await LauncherHost.channel.invokeMethod('capture', 'library');
      await tester.drag(find.text('Your library'), const Offset(0, 550));
      await tester.pumpAndSettle();
      expect(find.text('Your library'), findsNothing);
      await tester.tap(find.byKey(const Key('open-drawer')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('close-drawer')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await host.launch(settings.id);
    },
  );
}
