import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

class SakuraDesign {
  static const seed = Color(0xff7161b6);
  static const spring = SpringDescription(mass: 1, stiffness: 290, damping: 32);
  static ThemeData theme(Brightness brightness, {Color accent = seed}) =>
      ThemeData(
        useMaterial3: true,
        brightness: brightness,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          brightness: brightness,
        ),
        cardTheme: const CardThemeData(
          elevation: 0,
          margin: EdgeInsets.symmetric(vertical: 6),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(26)),
          ),
        ),
      );
}

class SakuraMark extends StatelessWidget {
  const SakuraMark({super.key, this.size = 32});
  final double size;
  @override
  Widget build(BuildContext context) =>
      CustomPaint(size: Size.square(size), painter: _PetalPainter());
}

class _PetalPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    canvas.translate(size.width / 2, size.height / 2);
    final p = Paint()..color = const Color(0xff7964a6);
    for (int i = 0; i < 5; i++) {
      canvas.save();
      canvas.rotate(i * math.pi * 2 / 5);
      canvas.drawOval(
        Rect.fromCenter(
          center: Offset(0, -size.height * .24),
          width: size.width * .28,
          height: size.height * .46,
        ),
        p,
      );
      canvas.restore();
    }
    canvas.drawCircle(
      Offset.zero,
      size.width * .10,
      Paint()..color = const Color(0xffe9daed),
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

String? glyphKind(String label) {
  final value = label.toLowerCase().replaceFirst(RegExp(r'^sakura '), '');
  for (final entry in <String, String>{
    'camera': 'camera',
    'settings': 'settings',
    'phone': 'phone',
    'dialer': 'phone',
    'messages': 'messages',
    'messaging': 'messages',
    'files': 'files',
    'file manager': 'files',
    'chrome': 'browser',
    'browser': 'browser',
    'clock': 'clock',
    'music': 'music',
    'terminal': 'terminal',
  }.entries) {
    if (value == entry.key) return entry.value;
  }
  return null;
}

class ThemedAppIcon extends StatelessWidget {
  const ThemedAppIcon({super.key, required this.kind});
  final String kind;
  @override
  Widget build(BuildContext context) {
    final hue = switch (kind) {
      'camera' => const Color(0xffaf9bd1),
      'settings' => const Color(0xff8497c3),
      'phone' => const Color(0xff7aaf96),
      'messages' => const Color(0xff8daabb),
      'files' => const Color(0xffcba77b),
      'music' => const Color(0xffcd93a7),
      _ => const Color(0xff9991c4),
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color.lerp(hue, Colors.white, .24)!, hue],
        ),
        border: Border.all(color: Colors.white.withValues(alpha: .5)),
        borderRadius: BorderRadius.circular(18),
      ),
      child: CustomPaint(painter: _GlyphPainter(kind)),
    );
  }
}

class _GlyphPainter extends CustomPainter {
  const _GlyphPainter(this.kind);
  final String kind;
  @override
  void paint(Canvas c, Size size) {
    c.scale(size.width / 54, size.height / 54);
    final p = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.3
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    switch (kind) {
      case 'camera':
        c.drawRRect(
          RRect.fromRectAndRadius(
            const Rect.fromLTWH(12, 18, 30, 23),
            const Radius.circular(6),
          ),
          p,
        );
        c.drawCircle(const Offset(27, 29.5), 7, p);
        c.drawLine(const Offset(21, 14), const Offset(33, 14), p);
        c.drawCircle(const Offset(37, 23), 1, p..style = PaintingStyle.fill);
      case 'settings':
        final path = Path();
        for (int i = 0; i <= 64; i++) {
          final a = i * math.pi * 2 / 64;
          final r = 15 + (i % 8 < 4 ? 1.8 : -1.8);
          final point = Offset(27 + math.cos(a) * r, 27 + math.sin(a) * r);
          if (i == 0) {
            path.moveTo(point.dx, point.dy);
          } else {
            path.lineTo(point.dx, point.dy);
          }
        }
        path.close();
        c.drawPath(path, p);
        c.drawCircle(const Offset(27, 27), 6, p);
      case 'browser':
        c.drawCircle(const Offset(27, 27), 16, p);
        c.drawOval(const Rect.fromLTWH(20, 11, 14, 32), p);
        c.drawLine(const Offset(12, 27), const Offset(42, 27), p);
        c.drawArc(const Rect.fromLTWH(12, 16, 30, 14), 0, math.pi, false, p);
      case 'phone':
        final path = Path()
          ..moveTo(15, 12)
          ..cubicTo(8, 20, 18, 40, 34, 42)
          ..quadraticBezierTo(40, 43, 42, 35)
          ..lineTo(33, 30)
          ..lineTo(29, 34)
          ..quadraticBezierTo(22, 30, 20, 23)
          ..lineTo(24, 19)
          ..lineTo(19, 12)
          ..close();
        c.drawPath(path, p);
      case 'messages':
        final path = Path()
          ..moveTo(19, 13)
          ..lineTo(36, 13)
          ..quadraticBezierTo(43, 13, 43, 21)
          ..lineTo(43, 32)
          ..quadraticBezierTo(43, 39, 36, 39)
          ..lineTo(24, 39)
          ..lineTo(14, 44)
          ..lineTo(16, 36)
          ..quadraticBezierTo(11, 33, 11, 29)
          ..lineTo(11, 21)
          ..quadraticBezierTo(11, 13, 19, 13);
        c.drawPath(path, p);
        for (final x in [20.0, 27.0, 34.0]) c.drawCircle(Offset(x, 26), 1, p);
      case 'files':
        final path = Path()
          ..moveTo(12, 18)
          ..lineTo(12, 38)
          ..quadraticBezierTo(12, 41, 16, 41)
          ..lineTo(39, 41)
          ..quadraticBezierTo(43, 41, 43, 37)
          ..lineTo(43, 21)
          ..lineTo(28, 21)
          ..lineTo(24, 15)
          ..lineTo(15, 15)
          ..quadraticBezierTo(12, 15, 12, 18)
          ..close();
        c.drawPath(path, p);
        c.drawLine(const Offset(17, 28), const Offset(37, 28), p);
      case 'clock':
        c.drawCircle(const Offset(27, 27), 16, p);
        c.drawLine(const Offset(27, 27), const Offset(27, 17), p);
        c.drawLine(const Offset(27, 27), const Offset(35, 31), p);
        c.drawCircle(const Offset(27, 27), 1.7, p..style = PaintingStyle.fill);
      case 'music':
        final path = Path()
          ..moveTo(23, 36)
          ..lineTo(23, 16)
          ..lineTo(39, 12)
          ..lineTo(39, 32);
        c.drawPath(path, p);
        c.drawOval(const Rect.fromLTWH(14, 32, 9, 7), p);
        c.drawOval(const Rect.fromLTWH(30, 29, 9, 7), p);
        c.drawLine(const Offset(23, 22), const Offset(39, 18), p);
      default:
        final path = Path()
          ..moveTo(15, 20)
          ..lineTo(23, 27)
          ..lineTo(15, 34);
        c.drawPath(path, p);
        c.drawLine(const Offset(28, 35), const Offset(39, 35), p);
    }
  }

  @override
  bool shouldRepaint(covariant _GlyphPainter oldDelegate) =>
      kind != oldDelegate.kind;
}
