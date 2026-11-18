import 'package:flutter/services.dart';

class LaunchApp {
  const LaunchApp(this.id, this.label);
  final String id;
  final String label;
  factory LaunchApp.fromMap(Map<dynamic, dynamic> map) =>
      LaunchApp(map['id'] as String, map['label'] as String);
}

class LauncherHost {
  static const channel = MethodChannel('dev.sakura.home/launcher');
  Future<Map<dynamic, dynamic>> load() async =>
      await channel.invokeMapMethod<dynamic, dynamic>('load') ?? {};
  Future<Uint8List?> icon(String id) =>
      channel.invokeMethod<Uint8List>('icon', id);
  Future<void> launch(String id) => channel.invokeMethod('launch', id);
  Future<void> save(Map<String, dynamic> value) =>
      channel.invokeMethod('save', value);
  Future<void> action(String name, [String? id]) =>
      channel.invokeMethod(name, id);
  Future<List<int>> rank(List<LaunchApp> apps, String query) async =>
      (await channel.invokeListMethod<int>('search', {
        'labels': apps.map((a) => a.label).toList(),
        'query': query,
      })) ??
      [];
}
