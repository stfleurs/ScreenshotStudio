import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class NativeCaptureBridge {
  static const MethodChannel _channel = MethodChannel('com.screenshotstudio.app/capture');

  static void setOverlayCallback(Function(String mode) callback) {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onOverlayTriggered') {
        final mode = call.arguments as String? ?? 'instant';
        callback(mode);
      }
    });
  }

  static Future<bool> requestCapturePermission() async {
    try {
      final bool? granted = await _channel.invokeMethod<bool>('requestCapturePermission');
      debugPrint('requestCapturePermission -> $granted');
      return granted ?? false;
    } on PlatformException catch (e) {
      debugPrint('requestCapturePermission FAILED: ${e.code} ${e.message}');
      return false;
    }
  }

  static Future<bool> checkCapturePermission() async {
    try {
      final bool? granted = await _channel.invokeMethod<bool>('checkCapturePermission');
      return granted ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> checkOverlayPermission() async {
    try {
      final bool? granted = await _channel.invokeMethod<bool>('checkOverlayPermission');
      return granted ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<void> requestOverlayPermission() async {
    try {
      await _channel.invokeMethod('requestOverlayPermission');
    } on PlatformException catch (_) {}
  }

  static Future<void> showFloatingButton() async {
    try {
      await _channel.invokeMethod('showFloatingButton');
    } on PlatformException catch (_) {}
  }

  static Future<void> hideFloatingButton() async {
    try {
      await _channel.invokeMethod('hideFloatingButton');
    } on PlatformException catch (_) {}
  }

  static Future<String?> captureInstant() async {
    try {
      final String? path = await _channel.invokeMethod<String>('captureInstant');
      debugPrint('captureInstant -> $path');
      return path;
    } on PlatformException catch (e) {
      debugPrint('captureInstant FAILED: ${e.code} ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> stitchImages(List<String> paths) async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>('stitchImages', {'paths': paths});
      return res;
    } on PlatformException catch (_) {
      return null;
    }
  }
}
