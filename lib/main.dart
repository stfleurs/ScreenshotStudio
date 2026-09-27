import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:path_provider/path_provider.dart';
import 'core/platform/native_capture_bridge.dart';
import 'features/viewer/editor_viewer_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ScreenshotStudioApp());
}

class ScreenshotStudioApp extends StatelessWidget {
  const ScreenshotStudioApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Screenshot Studio',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6750A4),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool _isProjectionGranted = false;
  bool _isOverlayGranted = false;
  bool _isFloatingButtonVisible = false;
  bool _isProcessing = false;
  String? _statusMessage;

  @override
  void initState() {
    super.initState();
    _checkPermissions();
    NativeCaptureBridge.setOverlayCallback((mode) {
      if (mode == 'instant') {
        _triggerInstantCapture();
      }
    });
  }

  Future<void> _checkPermissions() async {
    final overlay = await NativeCaptureBridge.checkOverlayPermission();
    final projection = await NativeCaptureBridge.checkCapturePermission();
    if (!mounted) return;
    setState(() {
      _isOverlayGranted = overlay;
      _isProjectionGranted = projection;
    });
  }

  Future<void> _requestProjection() async {
    final granted = await NativeCaptureBridge.requestCapturePermission();
    setState(() {
      _isProjectionGranted = granted;
      _statusMessage = granted ? 'Screen capture permission granted!' : 'Permission denied';
    });
  }

  Future<void> _requestOverlay() async {
    await NativeCaptureBridge.requestOverlayPermission();
    await _checkPermissions();
  }

  Future<void> _toggleFloatingButton() async {
    if (!_isFloatingButtonVisible) {
      await NativeCaptureBridge.showFloatingButton();
      setState(() => _isFloatingButtonVisible = true);
    } else {
      await NativeCaptureBridge.hideFloatingButton();
      setState(() => _isFloatingButtonVisible = false);
    }
  }

  Future<void> _triggerInstantCapture() async {
    setState(() {
      _isProcessing = true;
      _statusMessage = 'Capturing screen...';
    });

    final path = await NativeCaptureBridge.captureInstant();
    await _checkPermissions();
    if (!mounted) return;
    setState(() => _isProcessing = false);

    if (path != null) {
      setState(() => _statusMessage = 'Screenshot captured!');
      Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => EditorViewerScreen(imagePath: path),
        ),
      );
    } else {
      setState(() {
        _statusMessage = 'Capture cancelled or failed. Please allow screen recording permission.';
      });
    }
  }

  /// Technical Spike C verification: creates synthetic overlapping images and runs native stitching
  Future<void> _runStitchingSpike() async {
    setState(() {
      _isProcessing = true;
      _statusMessage = 'Running synthetic stitching spike...';
    });

    try {
      final tempDir = await getTemporaryDirectory();
      final spikeDir = Directory('${tempDir.path}/spike_test')..createSync(recursive: true);

      // Create 3 synthetic overlapping bitmaps
      // Frame 1: rows 0 -> 800
      // Frame 2: rows 500 -> 1300 (300px overlap)
      // Frame 3: rows 1000 -> 1800 (300px overlap)
      const width = 600;
      const height = 800;
      const overlapPx = 300;

      final framePaths = <String>[];

      for (int i = 0; i < 3; i++) {
        final image = img.Image(width: width, height: height);
        final startY = i * (height - overlapPx);

        for (int y = 0; y < height; y++) {
          final globalY = startY + y;
          // Gradient pattern with horizontal stripe markers to prove alignment
          final r = (globalY % 256);
          final g = ((globalY ~/ 2) % 256);
          final b = ((globalY ~/ 3) % 256);

          for (int x = 0; x < width; x++) {
            if (globalY % 100 == 0) {
              image.setPixelRgb(x, y, 255, 255, 255); // landmark stripe
            } else {
              image.setPixelRgb(x, y, r, g, b);
            }
          }
        }

        final file = File('${spikeDir.path}/frame_$i.png');
        file.writeAsBytesSync(img.encodePng(image));
        framePaths.add(file.path);
      }

      // Execute native stitching engine
      final result = await NativeCaptureBridge.stitchImages(framePaths);

      setState(() => _isProcessing = false);

      if (result != null && mounted) {
        final outputPath = result['path'] as String;
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => EditorViewerScreen(
              imagePath: outputPath,
              isLongScreenshot: true,
            ),
          ),
        );
      } else {
        setState(() => _statusMessage = 'Stitching engine returned null');
      }
    } catch (e) {
      setState(() {
        _isProcessing = false;
        _statusMessage = 'Stitching error: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Screenshot Studio'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              elevation: 2,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Capture Setup & Permissions',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    const SizedBox(height: 12),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        _isProjectionGranted ? Icons.check_circle : Icons.screen_share,
                        color: _isProjectionGranted ? Colors.green : Colors.orange,
                      ),
                      title: const Text('MediaProjection (Screen Recording)'),
                      subtitle: Text(_isProjectionGranted ? 'Ready' : 'Required for capture'),
                      trailing: ElevatedButton(
                        onPressed: _requestProjection,
                        child: Text(_isProjectionGranted ? 'Granted' : 'Grant'),
                      ),
                    ),
                    const Divider(),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        _isOverlayGranted ? Icons.check_circle : Icons.layers,
                        color: _isOverlayGranted ? Colors.green : Colors.orange,
                      ),
                      title: const Text('Floating Overlay Window'),
                      subtitle: Text(_isOverlayGranted ? 'Ready' : 'Required for floating button'),
                      trailing: ElevatedButton(
                        onPressed: _requestOverlay,
                        child: Text(_isOverlayGranted ? 'Granted' : 'Grant'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 20),
            if (_statusMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.deepPurple.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.deepPurple),
                ),
                child: Text(_statusMessage!, textAlign: TextAlign.center),
              ),
              const SizedBox(height: 20),
            ],
            Text(
              'Capture Controls & Spikes',
              style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Colors.grey),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _isProcessing ? null : _triggerInstantCapture,
              icon: const Icon(Icons.camera_alt),
              label: const Text('Spike A: Instant Capture (Direct)'),
              style: FilledButton.styleFrom(padding: const EdgeInsets.symmetric(vertical: 14)),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: _isProcessing ? null : _toggleFloatingButton,
              icon: Icon(_isFloatingButtonVisible ? Icons.visibility_off : Icons.touch_app),
              label: Text(_isFloatingButtonVisible ? 'Hide Floating Button' : 'Show Floating Button'),
              style: OutlinedButton.styleFrom(padding: const EdgeInsets.symmetric(vertical: 14)),
            ),
            const SizedBox(height: 12),
            ElevatedButton.icon(
              onPressed: _isProcessing ? null : _runStitchingSpike,
              icon: const Icon(Icons.auto_awesome_mosaic),
              label: const Text('Spike C: Test Stitching Engine (Synthetic)'),
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.purple.shade900,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
