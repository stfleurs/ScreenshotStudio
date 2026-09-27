import 'package:flutter_test/flutter_test.dart';
import 'package:screenshot_studio/main.dart';

void main() {
  testWidgets('Screenshot Studio initial load smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const ScreenshotStudioApp());
    expect(find.text('Screenshot Studio'), findsWidgets);
    expect(find.text('Capture Setup & Permissions'), findsOneWidget);
  });
}
