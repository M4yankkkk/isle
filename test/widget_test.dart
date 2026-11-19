import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:isle/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.isle/app');
  late Map<String, dynamic> settings;
  late bool permission;
  late List<String> calls;
  setUp(() {
    permission = false;
    calls = [];
    settings = {
      'width': 110,
      'height': 34,
      'offset': 0,
      'horizontal': 0,
      'touchExtension': 30,
      'timeout': 5,
      'alwaysVisible': true,
      'charging': true,
      'media': true,
      'notifications': false,
      'haptics': true,
      'reducedMotion': false,
      'outline': false,
      'autoHide': false,
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          if (call.method == 'status') {
            return {
              'permission': permission,
              'running': false,
              'settings': settings,
            };
          }
          if (call.method == 'save') {
            settings.addAll(Map<String, dynamic>.from(call.arguments as Map));
            return settings;
          }
          return null;
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  testWidgets('starting is gated by overlay permission', (tester) async {
    await tester.pumpWidget(const IsleApp());
    await tester.pump();
    await tester.scrollUntilVisible(find.text('Turn on Isle'), 150);
    expect(
      tester
          .widget<FilledButton>(
            find.widgetWithText(FilledButton, 'Turn on Isle'),
          )
          .onPressed,
      isNull,
    );
    expect(calls, isNot(contains('start')));
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('granted overlay permission starts native service', (
    tester,
  ) async {
    permission = true;
    await tester.pumpWidget(const IsleApp());
    await tester.pump();
    await tester.scrollUntilVisible(find.text('Turn on Isle'), 150);
    await tester.tap(find.text('Turn on Isle'));
    await tester.pump();
    expect(calls, contains('start'));
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('appearance toggle persists through native bridge', (
    tester,
  ) async {
    await tester.pumpWidget(const IsleApp());
    await tester.pump();
    await tester.tap(find.text('Appearance'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(Switch).first);
    await tester.pump();
    expect(settings['alwaysVisible'], false);
    expect(calls, contains('save'));
    // Returning to another tab does not overwrite the saved choice with defaults.
    await tester.tap(find.text('Island'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Appearance'));
    await tester.pumpAndSettle();
    expect(tester.widget<Switch>(find.byType(Switch).first).value, false);
    await tester.pumpWidget(const SizedBox());
  });
}
