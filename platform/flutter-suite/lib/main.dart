import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:sakura_design/sakura_design.dart';
import 'calculator.dart';

const kind = String.fromEnvironment('SAKURA_APP', defaultValue: 'settings');
const names = {
  'settings': 'Settings',
  'files': 'Files',
  'phone': 'Phone',
  'messages': 'Messages',
  'contacts': 'Contacts',
  'gallery': 'Gallery',
  'camera': 'Camera',
  'clock': 'Clock',
  'calculator': 'Calculator',
  'calendar': 'Calendar',
  'music': 'Music',
  'recorder': 'Recorder',
  'notes': 'Notes',
  'installer': 'Installer',
  'wallpapers': 'Wallpapers',
  'updater': 'Updater',
  'terminal': 'Terminal',
  'coremanager': 'Core Manager',
  'patch': 'Patch',
};
const system = MethodChannel('dev.sakura/system');
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SakuraSuite());
}

class SakuraSuite extends StatefulWidget {
  const SakuraSuite({super.key});
  @override
  State<SakuraSuite> createState() => _SuiteState();
}

class _SuiteState extends State<SakuraSuite> with WidgetsBindingObserver {
  List<Map<dynamic, dynamic>> items = [];
  List<Map<String, dynamic>> notes = [];
  Map<dynamic, dynamic> info = {};
  String nativeHardware = "", nativeStatus = "";
  final input = TextEditingController(), body = TextEditingController();
  final folderStack = <String?>[];
  String? folder, selected, error;
  String expression = '', answer = '', output = '', staged = '';
  bool busy = false, recording = false;
  Timer? timer;
  int seconds = 0;
  ThemeMode theme = ThemeMode.system;
  Color accent = SakuraDesign.seed;
  final images = <String, Future<Uint8List?>>{};
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    loadAccent();
  }

  Future<void> loadAccent() async {
    final value = await invoke<int>("accent");
    if (value != null && mounted) setState(() => accent = Color(value));
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    input.dispose();
    body.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) {
      timer?.cancel();
      if (mounted) setState(() => recording = false);
    } else if (kind == 'recorder') {
      refresh();
    }
  }

  Future<T?> invoke<T>(String name, [dynamic args]) async {
    try {
      return await system.invokeMethod<T>(name, args);
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message ?? e.code);
      return null;
    }
  }

  Future<void> refresh() async {
    if (!mounted) return;
    setState(() {
      busy = true;
      error = null;
    });
    if (kind == 'notes') {
      final value = await invoke<String>('notes');
      if (value != null)
        notes = List<Map<String, dynamic>>.from(
          (jsonDecode(value) as List).map((v) => Map<String, dynamic>.from(v)),
        );
    } else if ([
      'contacts',
      'calendar',
      'gallery',
      'music',
      'recorder',
      'files',
    ].contains(kind)) {
      final value = await invoke<List<dynamic>>('list', folder);
      if (value != null)
        items = value.map((v) => Map<dynamic, dynamic>.from(v)).toList();
    } else if (['settings', 'coremanager', 'updater'].contains(kind)) {
      info = await invoke<Map<dynamic, dynamic>>('info') ?? {};
      if (kind == 'coremanager') {
        final hardware = await invoke<Map<dynamic, dynamic>>(
          'core',
          'hardware',
        );
        final status = await invoke<Map<dynamic, dynamic>>('core', 'status');
        nativeHardware =
            hardware?['output']?.toString() ??
            'Native hardware information unavailable';
        nativeStatus =
            status?['output']?.toString() ??
            'Private daemon information unavailable';
      }
    }
    if (mounted) setState(() => busy = false);
  }

  Future<void> action(String name, {String? value, String? message}) async {
    await invoke('action', {
      'name': name,
      'value': value ?? '',
      'body': message ?? '',
    });
  }

  Future<void> pick() async {
    final value = await invoke<String>('pick');
    if (value == null) return;
    if (mounted) setState(() => selected = value);
    if (kind == 'files') {
      folder = null;
      folderStack.clear();
      await refresh();
    }
    if (kind == 'wallpapers') {
      final data = await invoke<Uint8List>('thumbnail', value);
      if (data != null) {
        final palette = await ColorScheme.fromImageProvider(
          provider: MemoryImage(data),
          brightness: Brightness.light,
        );
        if (mounted) setState(() => accent = palette.primary);
      }
    }
  }

  Future<void> saveNote(BuildContext context, {int? index}) async {
    final title = TextEditingController(
      text: index == null ? '' : notes[index]['title'] as String?,
    );
    final text = TextEditingController(
      text: index == null ? '' : notes[index]['body'] as String?,
    );
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(index == null ? 'New note' : 'Edit note'),
        content: SizedBox(
          width: 500,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: title,
                decoration: const InputDecoration(labelText: 'Title'),
              ),
              TextField(
                controller: text,
                decoration: const InputDecoration(labelText: 'Note'),
                minLines: 3,
                maxLines: 8,
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (saved == true) {
      final note = {
        'title': title.text.trim().isEmpty ? 'Untitled' : title.text.trim(),
        'body': text.text,
        'modified': DateTime.now().toIso8601String(),
      };
      if (index == null) {
        notes.insert(0, note);
      } else {
        notes[index] = note;
      }
      await invoke('saveNotes', jsonEncode(notes));
      if (mounted) setState(() {});
    }
    title.dispose();
    text.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Sakura ${names[kind]}',
    debugShowCheckedModeBanner: false,
    theme: SakuraDesign.theme(Brightness.light, accent: accent),
    darkTheme: SakuraDesign.theme(Brightness.dark, accent: accent),
    themeMode: theme,
    home: Builder(
      builder: (context) => Scaffold(
        appBar: AppBar(
          title: Row(
            children: [
              const SakuraMark(size: 24),
              const SizedBox(width: 12),
              Text(names[kind] ?? kind),
            ],
          ),
          actions: [
            IconButton(
              tooltip: 'Appearance',
              onPressed: () => setState(
                () => theme = theme == ThemeMode.dark
                    ? ThemeMode.light
                    : ThemeMode.dark,
              ),
              icon: const Icon(Icons.contrast_rounded),
            ),
            IconButton(
              tooltip: 'Refresh',
              onPressed: refresh,
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
        ),
        body: SafeArea(
          child: Column(
            children: [
              if (busy) const LinearProgressIndicator(),
              if (error != null)
                MaterialBanner(
                  content: Text(error!),
                  actions: [
                    TextButton(
                      onPressed: () => setState(() => error = null),
                      child: const Text('Dismiss'),
                    ),
                  ],
                ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: content(context),
                ),
              ),
            ],
          ),
        ),
        floatingActionButton: switch (kind) {
          'notes' => FloatingActionButton.extended(
            onPressed: () => saveNote(context),
            icon: const Icon(Icons.add),
            label: const Text('Note'),
          ),
          'contacts' => FloatingActionButton(
            onPressed: () => action('contact'),
            child: const Icon(Icons.person_add_rounded),
          ),
          'calendar' => FloatingActionButton(
            onPressed: () => action('event'),
            child: const Icon(Icons.event_rounded),
          ),
          _ => null,
        },
      ),
    ),
  );
  Widget card(Widget child) => Card(
    child: Padding(padding: const EdgeInsets.all(20), child: child),
  );
  Widget content(BuildContext context) {
    switch (kind) {
      case 'calculator':
        return calculator();
      case 'notes':
        return notes.isEmpty
            ? Center(
                child: FilledButton.icon(
                  onPressed: () => saveNote(context),
                  icon: const Icon(Icons.edit_note),
                  label: const Text('Write your first note'),
                ),
              )
            : ListView.builder(
                itemCount: notes.length,
                itemBuilder: (context, i) => Card(
                  child: ListTile(
                    title: Text(notes[i]['title']),
                    subtitle: Text(
                      notes[i]['body'],
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                    ),
                    onTap: () => saveNote(context, index: i),
                    trailing: IconButton(
                      tooltip: 'Delete note',
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () async {
                        final yes = await confirm(context, 'Delete this note?');
                        if (yes) {
                          notes.removeAt(i);
                          await invoke('saveNotes', jsonEncode(notes));
                          if (mounted) setState(() {});
                        }
                      },
                    ),
                  ),
                ),
              );
      case 'phone':
        return ListView(
          children: [
            const SizedBox(height: 24),
            TextField(
              controller: input,
              keyboardType: TextInputType.phone,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 32),
              decoration: const InputDecoration(
                hintText: 'Phone number',
                border: InputBorder.none,
              ),
            ),
            const SizedBox(height: 24),
            GridView.count(
              crossAxisCount: 3,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              childAspectRatio: 1.4,
              children: [
                for (final digit in [
                  '1',
                  '2',
                  '3',
                  '4',
                  '5',
                  '6',
                  '7',
                  '8',
                  '9',
                  '*',
                  '0',
                  '#',
                ])
                  Padding(
                    padding: const EdgeInsets.all(5),
                    child: FilledButton.tonal(
                      onPressed: () {
                        input.text += digit;
                        HapticFeedback.selectionClick();
                      },
                      child: Text(digit, style: const TextStyle(fontSize: 28)),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: () => action('dial', value: input.text),
              icon: const Icon(Icons.call),
              label: const Text('Continue to secure Android dialer'),
            ),
            const SizedBox(height: 8),
            const Text(
              'Android handles calls, emergency calling and in-call controls.',
              textAlign: TextAlign.center,
            ),
          ],
        );
      case 'messages':
        return ListView(
          children: [
            card(
              Column(
                children: [
                  TextField(
                    controller: input,
                    keyboardType: TextInputType.phone,
                    decoration: const InputDecoration(labelText: 'Recipient'),
                  ),
                  TextField(
                    controller: body,
                    minLines: 4,
                    maxLines: 10,
                    decoration: const InputDecoration(labelText: 'Message'),
                  ),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: () =>
                        action('sms', value: input.text, message: body.text),
                    icon: const Icon(Icons.send_rounded),
                    label: const Text('Review in Android messaging'),
                  ),
                ],
              ),
            ),
            const Text(
              'The Android SMS application remains responsible for sending, receiving and default-SMS permissions.',
            ),
          ],
        );
      case 'contacts':
      case 'calendar':
        return dataList(context);
      case 'files':
        return Column(
          children: [
            Row(
              children: [
                if (folderStack.isNotEmpty)
                  IconButton(
                    onPressed: () {
                      folder = folderStack.removeLast();
                      refresh();
                    },
                    icon: const Icon(Icons.arrow_back),
                  ),
                Expanded(
                  child: TextButton.icon(
                    onPressed: pick,
                    icon: const Icon(Icons.folder_open_rounded),
                    label: const Text('Choose accessible folder'),
                  ),
                ),
                IconButton(
                  tooltip: 'New folder',
                  onPressed: () async {
                    final name = await ask(context, 'Folder name');
                    if (name != null) {
                      await invoke('file', {
                        'action': 'folder',
                        'name': name,
                        'uri': folder,
                      });
                      await refresh();
                    }
                  },
                  icon: const Icon(Icons.create_new_folder_outlined),
                ),
              ],
            ),
            Expanded(child: dataList(context)),
          ],
        );
      case 'gallery':
        return Column(
          children: [
            TextButton.icon(
              onPressed: pick,
              icon: const Icon(Icons.photo_library_outlined),
              label: const Text('Open selected image'),
            ),
            if (selected != null)
              SizedBox(height: 200, child: thumbnail(selected!)),
            Expanded(
              child: items.isEmpty
                  ? const Center(
                      child: Text(
                        'No accessible images. Use the picker or grant photo access.',
                      ),
                    )
                  : GridView.builder(
                      itemCount: items.length,
                      gridDelegate:
                          const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 3,
                            crossAxisSpacing: 8,
                            mainAxisSpacing: 8,
                          ),
                      itemBuilder: (context, i) => InkWell(
                        onTap: () => invoke('open', items[i]['id']),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(18),
                          child: thumbnail(items[i]['id']),
                        ),
                      ),
                    ),
            ),
          ],
        );
      case 'camera':
        return ListView(
          children: [
            card(
              Column(
                children: [
                  const Icon(Icons.camera_alt_outlined, size: 64),
                  const SizedBox(height: 20),
                  const Text('Capture with your device’s camera provider.'),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: () async {
                      final uri = await invoke<String>('capture');
                      if (uri != null && mounted)
                        setState(() => selected = uri);
                    },
                    icon: const Icon(Icons.camera),
                    label: const Text('Take photo'),
                  ),
                ],
              ),
            ),
            if (selected != null)
              SizedBox(height: 400, child: thumbnail(selected!)),
            if (selected != null)
              TextButton(
                onPressed: () => invoke('open', selected),
                child: const Text('View photo'),
              ),
          ],
        );
      case 'clock':
        return ListView(
          children: [
            card(
              Column(
                children: [
                  Text(
                    TimeOfDay.now().format(context),
                    style: const TextStyle(
                      fontSize: 64,
                      fontWeight: FontWeight.w200,
                    ),
                  ),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: () async {
                      final time = await showTimePicker(
                        context: context,
                        initialTime: TimeOfDay.now(),
                      );
                      if (time != null)
                        await action(
                          'alarm',
                          value: '${time.hour}:${time.minute}',
                        );
                    },
                    icon: const Icon(Icons.alarm_add_rounded),
                    label: const Text('Create Android alarm'),
                  ),
                  TextButton(
                    onPressed: () => action('alarms'),
                    child: const Text('Manage alarms'),
                  ),
                ],
              ),
            ),
            const Text(
              'Android’s alarm provider manages scheduling, ringing and reboot persistence.',
            ),
          ],
        );
      case 'music':
        return Column(
          children: [
            const Text('Playback stops when this app leaves the foreground.'),
            TextButton.icon(
              onPressed: () => invoke('stop'),
              icon: const Icon(Icons.stop_circle_outlined),
              label: const Text('Stop playback'),
            ),
            Expanded(child: dataList(context)),
          ],
        );
      case 'recorder':
        return Column(
          children: [
            card(
              Column(
                children: [
                  Text(
                    recording ? 'Recording · ${seconds}s' : 'Ready to record',
                    style: const TextStyle(fontSize: 24),
                  ),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: () async {
                      if (recording) {
                        await invoke('stop');
                        timer?.cancel();
                        if (mounted) setState(() => recording = false);
                        await refresh();
                      } else {
                        final name = await invoke<String>('record');
                        if (name != null) {
                          if (mounted)
                            setState(() {
                              recording = true;
                              seconds = 0;
                            });
                          timer = Timer.periodic(const Duration(seconds: 1), (
                            _,
                          ) {
                            if (mounted) setState(() => seconds++);
                          });
                        }
                      }
                    },
                    icon: Icon(recording ? Icons.stop : Icons.mic),
                    label: Text(
                      recording ? 'Save recording' : 'Start recording',
                    ),
                  ),
                  const Text('Recording stops when the app is backgrounded.'),
                ],
              ),
            ),
            Expanded(child: dataList(context)),
          ],
        );
      case 'installer':
        return ListView(
          children: [
            card(
              Column(
                children: [
                  const Icon(Icons.install_mobile_rounded, size: 64),
                  const SizedBox(height: 20),
                  FilledButton(
                    onPressed: pick,
                    child: const Text('Select APK'),
                  ),
                  if (selected != null) ...[
                    const SizedBox(height: 12),
                    Text(Uri.parse(selected!).pathSegments.last),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: () => action('install', value: selected),
                      icon: const Icon(Icons.verified_user_outlined),
                      label: const Text('Review installation in Android'),
                    ),
                  ],
                ],
              ),
            ),
            const Text(
              'Android PackageInstaller verifies the package and signature and asks for your approval. No silent install or privileged permission grants.',
            ),
          ],
        );
      case 'wallpapers':
        return ListView(
          children: [
            card(
              Column(
                children: [
                  FilledButton.icon(
                    onPressed: pick,
                    icon: const Icon(Icons.wallpaper_rounded),
                    label: const Text('Choose image'),
                  ),
                  if (selected != null) ...[
                    const SizedBox(height: 20),
                    SizedBox(height: 400, child: thumbnail(selected!)),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () async {
                        await invoke('wallpaper', selected);
                      },
                      child: const Text('Apply Android wallpaper'),
                    ),
                  ],
                ],
              ),
            ),
            const Text(
              'The preview theme adapts to the selected image. Android’s dynamic-color system supplies system wallpaper colors.',
            ),
          ],
        );
      case 'terminal':
        return Column(
          children: [
            const Text(
              'Unprivileged command runner · 10 s / 64 KiB limit · no PTY',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: input,
              decoration: InputDecoration(
                hintText: 'id; uname -a',
                suffixIcon: IconButton(
                  icon: const Icon(Icons.play_arrow),
                  onPressed: () async {
                    final value = await invoke<Map<dynamic, dynamic>>(
                      'command',
                      input.text,
                    );
                    if (value != null && mounted)
                      setState(
                        () => output =
                            '${value['output']}\nExit: ${value['exit']}',
                      );
                  },
                ),
              ),
              onSubmitted: (text) async {
                final value = await invoke<Map<dynamic, dynamic>>(
                  'command',
                  text,
                );
                if (value != null && mounted)
                  setState(
                    () => output = '${value['output']}\nExit: ${value['exit']}',
                  );
              },
            ),
            Expanded(
              child: SingleChildScrollView(
                child: SelectableText(
                  output,
                  style: const TextStyle(fontFamily: 'monospace'),
                ),
              ),
            ),
          ],
        );
      case 'patch':
        Future<void> operation(String verb) async {
          final result = await invoke<String>('patch', {
            'verb': verb,
            'generation': staged,
          });
          if (result != null && mounted)
            setState(() {
              output = result;
              if (verb == 'stage') staged = result;
            });
        }
        return ListView(
          children: [
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Signed asset generations',
                    style: TextStyle(fontSize: 24),
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Changes stay in this app’s owned-file store. No system, kernel, boot or process-memory writes.',
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    onPressed: () async {
                      await pick();
                      if (selected == null || !context.mounted) return;
                      final trust = await showDialog<bool>(
                        context: context,
                        builder: (ctx) => AlertDialog(
                          title: const Text('Trust this public key?'),
                          content: const Text(
                            'Only select a 32-byte raw Ed25519 public key from an update publisher you trust. This authorizes its signed owned-file patches.',
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(ctx, false),
                              child: const Text('Cancel'),
                            ),
                            FilledButton(
                              onPressed: () => Navigator.pop(ctx, true),
                              child: const Text('Trust key'),
                            ),
                          ],
                        ),
                      );
                      if (trust == true) {
                        final value = await invoke<String>('patch', {
                          'verb': 'trustKey',
                          'uri': selected,
                        });
                        if (value != null && mounted)
                          setState(() => output = value);
                      }
                    },
                    child: const Text('Choose trusted public key'),
                  ),
                  FilledButton.tonal(
                    onPressed: () async {
                      await pick();
                      if (selected != null) {
                        final value = await invoke<String>('patch', {
                          'verb': 'import',
                          'uri': selected,
                        });
                        if (value != null && mounted)
                          setState(() => output = value);
                      }
                    },
                    child: const Text('Import .patch file'),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton(
                        onPressed: () => operation('verify'),
                        child: const Text('Verify signature & metadata'),
                      ),
                      FilledButton(
                        onPressed: () => operation('stage'),
                        child: const Text('Stage & verify hashes'),
                      ),
                      FilledButton(
                        onPressed: staged.isEmpty
                            ? null
                            : () async {
                                final yes = await showDialog<bool>(
                                  context: context,
                                  builder: (ctx) => AlertDialog(
                                    title: const Text(
                                      'Activate staged assets?',
                                    ),
                                    content: Text(staged),
                                    actions: [
                                      TextButton(
                                        onPressed: () =>
                                            Navigator.pop(ctx, false),
                                        child: const Text('Cancel'),
                                      ),
                                      FilledButton(
                                        onPressed: () =>
                                            Navigator.pop(ctx, true),
                                        child: const Text('Activate'),
                                      ),
                                    ],
                                  ),
                                );
                                if (yes == true) await operation('apply');
                              },
                        child: const Text('Activate owned assets'),
                      ),
                      OutlinedButton(
                        onPressed: () => operation('list'),
                        child: const Text('History'),
                      ),
                      OutlinedButton(
                        onPressed: () async {
                          final yes = await showDialog<bool>(
                            context: context,
                            builder: (ctx) => AlertDialog(
                              title: const Text('Rollback owned assets?'),
                              actions: [
                                TextButton(
                                  onPressed: () => Navigator.pop(ctx, false),
                                  child: const Text('Cancel'),
                                ),
                                FilledButton(
                                  onPressed: () => Navigator.pop(ctx, true),
                                  child: const Text('Rollback'),
                                ),
                              ],
                            ),
                          );
                          if (yes == true) await operation('rollback');
                        },
                        child: const Text('Rollback'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  SelectableText(output),
                ],
              ),
            ),
            const Text(
              'Requires the GSI’s /system/bin/sakura CLI. A standalone APK on stock HyperOS reports the missing backend. No service restart or system OTA is performed.',
            ),
          ],
        );
      case 'updater':
        return ListView(
          children: [
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('SakuraOS 0.2.0', style: TextStyle(fontSize: 28)),
                  const SizedBox(height: 12),
                  const Text(
                    'UNTESTED DEVELOPER BUILD — FIRST BOOT NOT VERIFIED',
                  ),
                  const SizedBox(height: 16),
                  Text('Android ${info['android'] ?? 'unavailable'}'),
                  const SizedBox(height: 12),
                  const Text(
                    'No OTA server or system-image installer is configured. This app does not flash partitions.',
                  ),
                ],
              ),
            ),
            const Text(
              'Use the build report and recovery guide supplied with the image.',
            ),
          ],
        );
      default:
        return ListView(
          children: [
            card(
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    info['device']?.toString() ??
                        'Device information unavailable',
                    style: const TextStyle(fontSize: 24),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Android ${info['android'] ?? 'unavailable'} · Kernel ${info['kernel'] ?? 'unavailable'}',
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Battery: ${info['battery'] ?? 'unavailable'}% · charging source: ${info['charging'] ?? 'unavailable'}',
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Free storage: ${formatBytes(info['storageFree'])} / ${formatBytes(info['storageTotal'])}',
                  ),
                ],
              ),
            ),
            if (kind == 'coremanager') ...[
              card(SelectableText(nativeHardware)),
              card(SelectableText(nativeStatus)),
              card(
                SelectableText(
                  info['memory']?.toString() ?? 'Memory unavailable',
                ),
              ),
              card(
                Text(info['daemon']?.toString() ?? 'Daemon state unavailable'),
              ),
            ],
            for (final entry in {
              'display': 'Display',
              'network': 'Connections',
              'battery': 'Battery',
              'apps': 'Applications',
              'security': 'Security',
              'sound': 'Sound',
              'storage': 'Storage',
            }.entries)
              Card(
                child: ListTile(
                  title: Text(entry.value),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => action('settings', value: entry.key),
                ),
              ),
          ],
        );
    }
  }

  Widget dataList(BuildContext context) => items.isEmpty
      ? const Center(
          child: Text('No accessible items. Refresh to request access.'),
        )
      : ListView.builder(
          itemCount: items.length,
          itemBuilder: (context, i) {
            final item = items[i];
            final id = item['id'] as String;
            return Card(
              child: ListTile(
                leading: Icon(
                  item['folder'] == true
                      ? Icons.folder_rounded
                      : switch (kind) {
                          'contacts' => Icons.person_outline,
                          'calendar' => Icons.event_outlined,
                          'music' => Icons.music_note_outlined,
                          'recorder' => Icons.graphic_eq,
                          _ => Icons.insert_drive_file_outlined,
                        },
                ),
                title: Text(item['title']?.toString() ?? 'Untitled'),
                subtitle: Text(
                  kind == 'calendar'
                      ? DateTime.fromMillisecondsSinceEpoch(
                          int.tryParse(item['subtitle'].toString()) ?? 0,
                        ).toLocal().toString()
                      : item['subtitle']?.toString() ?? '',
                ),
                onTap: () async {
                  if (item['folder'] == true) {
                    folderStack.add(folder);
                    folder = id;
                    await refresh();
                  } else if (kind == 'contacts') {
                    await action('dial', value: item['subtitle'] as String?);
                  } else if (['music', 'recorder'].contains(kind)) {
                    await invoke('play', id);
                  } else {
                    await invoke('open', id);
                  }
                },
                onLongPress: kind == 'files'
                    ? () async {
                        final choice = await showModalBottomSheet<String>(
                          context: context,
                          showDragHandle: true,
                          builder: (ctx) => SafeArea(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                ListTile(
                                  title: const Text('Rename'),
                                  onTap: () => Navigator.pop(ctx, 'rename'),
                                ),
                                ListTile(
                                  title: const Text('Delete'),
                                  onTap: () => Navigator.pop(ctx, 'delete'),
                                ),
                              ],
                            ),
                          ),
                        );
                        if (choice == 'rename' && context.mounted) {
                          final name = await ask(context, 'New name');
                          if (name != null)
                            await invoke('file', {
                              'action': 'rename',
                              'uri': id,
                              'name': name,
                            });
                        }
                        if (choice == 'delete' &&
                            context.mounted &&
                            await confirm(context, 'Delete this item?')) {
                          await invoke('file', {'action': 'delete', 'uri': id});
                        }
                        await refresh();
                      }
                    : null,
              ),
            );
          },
        );
  Widget thumbnail(String uri) => FutureBuilder<Uint8List?>(
    future: images.putIfAbsent(uri, () => invoke<Uint8List>('thumbnail', uri)),
    builder: (context, snapshot) => snapshot.data == null
        ? const Center(child: Icon(Icons.image_outlined))
        : Image.memory(snapshot.data!, fit: BoxFit.cover),
  );
  Widget calculator() => Column(
    children: [
      Expanded(
        child: Align(
          alignment: Alignment.bottomRight,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                SelectableText(
                  expression.isEmpty ? '0' : expression,
                  style: const TextStyle(
                    fontSize: 40,
                    fontWeight: FontWeight.w300,
                  ),
                ),
                Text(answer, style: const TextStyle(fontSize: 26)),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ),
      ),
      Expanded(
        flex: 2,
        child: GridView.count(
          crossAxisCount: 4,
          childAspectRatio: 1.15,
          children: [
            for (final key in [
              'C',
              '(',
              ')',
              '⌫',
              '7',
              '8',
              '9',
              '÷',
              '4',
              '5',
              '6',
              '×',
              '1',
              '2',
              '3',
              '−',
              '0',
              '.',
              '=',
              '+',
            ])
              Padding(
                padding: const EdgeInsets.all(4),
                child: FilledButton.tonal(
                  onPressed: () => setState(() {
                    if (key == 'C') {
                      expression = '';
                      answer = '';
                    } else if (key == '⌫') {
                      if (expression.isNotEmpty)
                        expression = expression.substring(
                          0,
                          expression.length - 1,
                        );
                    } else if (key == '=') {
                      try {
                        answer = calculate(expression).toString();
                      } catch (e) {
                        answer = 'Invalid expression';
                      }
                    } else if (expression.length < 256) {
                      expression += key;
                    }
                  }),
                  child: Text(key, style: const TextStyle(fontSize: 24)),
                ),
              ),
          ],
        ),
      ),
    ],
  );
  Future<bool> confirm(BuildContext context, String question) async =>
      await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text(question),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Delete'),
            ),
          ],
        ),
      ) ??
      false;
  Future<String?> ask(BuildContext context, String title) async {
    final c = TextEditingController();
    final value = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(controller: c, autofocus: true, maxLength: 100),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, c.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    c.dispose();
    return value == null || value.isEmpty ? null : value;
  }

  String formatBytes(dynamic value) => value is num
      ? '${(value / 1073741824).toStringAsFixed(1)} GiB'
      : 'unavailable';
}
