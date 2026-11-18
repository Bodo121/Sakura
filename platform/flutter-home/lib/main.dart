import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/services.dart';
import 'platform.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks([
      'LiquidGlass Android',
    ], await rootBundle.loadString('assets/LIQUIDGLASS-LICENSE.txt'));
  });
  runApp(const SakuraHome());
}

class SakuraHome extends StatefulWidget {
  const SakuraHome({super.key, this.host});
  final LauncherHost? host;
  @override
  State<SakuraHome> createState() => _SakuraHomeState();
}

class _SakuraHomeState extends State<SakuraHome>
    with TickerProviderStateMixin, WidgetsBindingObserver {
  late final host = widget.host ?? LauncherHost();
  late final AnimationController drawer = AnimationController(
    vsync: this,
    lowerBound: 0,
    upperBound: 1,
  );
  final search = TextEditingController();
  final searchFocus = FocusNode();
  final iconCache = <String, Future<Uint8List?>>{};
  List<LaunchApp> apps = [], results = [];
  List<String> favorites = [], dock = [];
  Uint8List? wallpaper;
  bool loading = true,
      glass = true,
      motion = true,
      labels = true,
      rust = false,
      themedIcons = true;
  String theme = 'system';
  String? error;
  int columns = 4, searchGeneration = 0;
  Timer? clock;
  DateTime now = DateTime.now();
  bool get reduced =>
      !motion ||
      WidgetsBinding
          .instance
          .platformDispatcher
          .accessibilityFeatures
          .disableAnimations;
  List<LaunchApp> resolve(List<String> ids) => ids
      .map((id) => apps.where((a) => a.id == id).firstOrNull)
      .whereType<LaunchApp>()
      .toList();
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
    startClock();
    LauncherHost.channel.setMethodCallHandler((call) async {
      if (call.method == 'catalogChanged') await load();
      if (call.method == 'homePressed') {
        closeDrawer();
        searchFocus.unfocus();
      }
    });
  }

  void startClock() {
    clock?.cancel();
    clock = Timer.periodic(const Duration(seconds: 30), (_) {
      if (mounted) setState(() => now = DateTime.now());
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      setState(() => now = DateTime.now());
      startClock();
    } else {
      clock?.cancel();
    }
  }

  Future<void> load() async {
    try {
      final data = await host.load();
      if (!mounted) return;
      setState(() {
        apps = (data['apps'] as List? ?? [])
            .map((a) => LaunchApp.fromMap(a as Map))
            .toList();
        results = apps;
        favorites = List<String>.from(data['favorites'] ?? []);
        dock = List<String>.from(data['dock'] ?? []);
        // Seed the first install once. Removing all pins later remains a valid layout.
        if (data['initialized'] != true && favorites.isEmpty && dock.isEmpty) {
          favorites = apps.take(8).map((a) => a.id).toList();
          dock = apps
              .where(
                (a) => RegExp(
                  'phone|messages|camera|browser|chrome',
                  caseSensitive: false,
                ).hasMatch(a.label),
              )
              .take(4)
              .map((a) => a.id)
              .toList();
        }
        columns = (data['columns'] as int? ?? 4).clamp(4, 5);
        glass = data['glass'] != false;
        motion = data['motion'] != false;
        labels = data['labels'] != false;
        themedIcons = data['themedIcons'] != false;
        theme = data['theme'] as String? ?? 'system';
        rust = data['rust'] == true;
        wallpaper = data['wallpaper'] as Uint8List?;
        loading = false;
        error = null;
        iconCache.removeWhere((id, _) => !apps.any((a) => a.id == id));
      });
      await persist();
      if (search.text.isNotEmpty) await filter(search.text);
    } catch (e) {
      if (mounted)
        setState(() {
          error = 'Could not load your apps. $e';
          loading = false;
        });
    }
  }

  Future<void> persist() => host.save({
    'favorites': favorites,
    'dock': dock,
    'columns': columns,
    'glass': glass,
    'motion': motion,
    'labels': labels,
    'themedIcons': themedIcons,
    'theme': theme,
  });
  Future<void> filter(String query) async {
    final generation = ++searchGeneration;
    try {
      final indices = await host.rank(apps, query);
      if (mounted && generation == searchGeneration)
        setState(
          () => results = indices
              .where((i) => i >= 0 && i < apps.length)
              .map((i) => apps[i])
              .toList(),
        );
    } catch (_) {
      if (mounted && generation == searchGeneration)
        setState(
          () => results = apps
              .where(
                (a) =>
                    a.label.toLowerCase().contains(query.trim().toLowerCase()),
              )
              .toList(),
        );
    }
  }

  void settle(double target, [double velocity = 0]) {
    if (reduced) {
      drawer.value = target;
      return;
    }
    drawer.animateWith(
      SpringSimulation(
        const SpringDescription(mass: 1, stiffness: 290, damping: 32),
        drawer.value,
        target,
        velocity,
      ),
    );
  }

  void openDrawer() {
    HapticFeedback.selectionClick();
    settle(1);
  }

  void closeDrawer() {
    if (!mounted) return;
    searchFocus.unfocus();
    settle(0);
  }

  Future<void> launch(LaunchApp app) async {
    HapticFeedback.lightImpact();
    try {
      await host.launch(app.id);
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not open ${app.label}: $e')),
        );
    }
  }

  Future<void> safeAction(String action, [String? id]) async {
    try {
      await host.action(action, id);
    } catch (e) {
      if (mounted)
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  void dispose() {
    clock?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    LauncherHost.channel.setMethodCallHandler(null);
    drawer.dispose();
    search.dispose();
    searchFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final themeMode = switch (theme) {
      'dark' => ThemeMode.dark,
      'light' => ThemeMode.light,
      _ => ThemeMode.system,
    };
    ThemeData design(Brightness b) => ThemeData(
      useMaterial3: true,
      brightness: b,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xff7161b6),
        brightness: b,
      ),
      fontFamily: 'sans-serif',
      visualDensity: VisualDensity.standard,
    );
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Sakura Home',
      theme: design(Brightness.light),
      darkTheme: design(Brightness.dark),
      themeMode: themeMode,
      home: Builder(
        builder: (context) {
          final dark = Theme.of(context).brightness == Brightness.dark;
          return AnnotatedRegion<SystemUiOverlayStyle>(
            value: dark
                ? SystemUiOverlayStyle.light
                : SystemUiOverlayStyle.dark,
            child: PopScope(
              canPop: false,
              onPopInvokedWithResult: (didPop, result) {
                if (!didPop) closeDrawer();
              },
              child: Scaffold(
                backgroundColor: const Color(0xffd5d8ee),
                body: LayoutBuilder(
                  builder: (context, constraints) {
                    final h = constraints.maxHeight;
                    return AnimatedBuilder(
                      animation: drawer,
                      builder: (context, _) => Stack(
                        children: [
                          Positioned.fill(
                            child: wallpaper == null
                                ? const DecoratedBox(
                                    decoration: BoxDecoration(
                                      gradient: LinearGradient(
                                        begin: Alignment.topLeft,
                                        end: Alignment.bottomRight,
                                        colors: [
                                          Color(0xffe5dbea),
                                          Color(0xffb7c1e9),
                                          Color(0xff7973a6),
                                        ],
                                      ),
                                    ),
                                  )
                                : Image.memory(
                                    wallpaper!,
                                    fit: BoxFit.cover,
                                    gaplessPlayback: true,
                                  ),
                          ),
                          if (dark)
                            Positioned.fill(
                              child: ColoredBox(
                                color: Colors.black.withValues(alpha: .32),
                              ),
                            ),
                          Transform.scale(
                            scale: 1 - drawer.value * .045,
                            child: Opacity(
                              opacity: 1 - drawer.value * .35,
                              child: GestureDetector(
                                onVerticalDragUpdate: (d) {
                                  if (d.delta.dy < 0)
                                    drawer.value =
                                        (drawer.value - d.delta.dy / h).clamp(
                                          0,
                                          1,
                                        );
                                },
                                onVerticalDragEnd: (d) {
                                  if (drawer.value > .08 ||
                                      d.velocity.pixelsPerSecond.dy < -250)
                                    openDrawer();
                                  else
                                    settle(0);
                                },
                                onLongPress: () => customize(context),
                                child: SafeArea(
                                  child: Padding(
                                    padding: const EdgeInsets.fromLTRB(
                                      24,
                                      16,
                                      24,
                                      12,
                                    ),
                                    child: Column(
                                      children: [
                                        Row(
                                          children: [
                                            const SakuraMark(size: 26),
                                            const SizedBox(width: 8),
                                            Text(
                                              'sakura',
                                              style: TextStyle(
                                                fontSize: 18,
                                                fontWeight: FontWeight.w600,
                                                letterSpacing: 2,
                                                color: dark
                                                    ? Colors.white
                                                    : const Color(0xff333149),
                                              ),
                                            ),
                                            const Spacer(),
                                            IconButton(
                                              key: const Key('customize'),
                                              tooltip: 'Personalize',
                                              onPressed: () =>
                                                  customize(context),
                                              icon: const Icon(
                                                Icons.tune_rounded,
                                              ),
                                            ),
                                          ],
                                        ),
                                        const SizedBox(height: 22),
                                        Align(
                                          alignment: Alignment.centerLeft,
                                          child: GestureDetector(
                                            onTap: () => safeAction('clock'),
                                            child: Column(
                                              crossAxisAlignment:
                                                  CrossAxisAlignment.start,
                                              children: [
                                                Text(
                                                  '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}',
                                                  style: TextStyle(
                                                    fontSize: 76,
                                                    height: 1.05,
                                                    fontWeight: FontWeight.w200,
                                                    letterSpacing: -4,
                                                    color: dark
                                                        ? Colors.white
                                                        : const Color(
                                                            0xff302d49,
                                                          ),
                                                  ),
                                                ),
                                                const SizedBox(height: 8),
                                                Text(
                                                  MaterialLocalizations.of(
                                                    context,
                                                  ).formatFullDate(now),
                                                  style: TextStyle(
                                                    fontSize: 14,
                                                    color: dark
                                                        ? Colors.white70
                                                        : const Color(
                                                            0xff4f4a67,
                                                          ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                        const SizedBox(height: 24),
                                        Align(
                                          alignment: Alignment.centerLeft,
                                          child: Container(
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 14,
                                              vertical: 10,
                                            ),
                                            decoration: BoxDecoration(
                                              color: Colors.white.withValues(
                                                alpha: dark ? .12 : .32,
                                              ),
                                              borderRadius:
                                                  BorderRadius.circular(18),
                                              border: Border.all(
                                                color: Colors.white.withValues(
                                                  alpha: .35,
                                                ),
                                              ),
                                            ),
                                            child: Row(
                                              mainAxisSize: MainAxisSize.min,
                                              children: [
                                                const Icon(
                                                  Icons.apps_rounded,
                                                  size: 17,
                                                ),
                                                const SizedBox(width: 8),
                                                Text(
                                                  '${apps.length} apps · Your space',
                                                  style: const TextStyle(
                                                    fontSize: 12,
                                                    fontWeight: FontWeight.w500,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                        const SizedBox(height: 24),
                                        Expanded(
                                          child: loading
                                              ? const Center(
                                                  child:
                                                      CircularProgressIndicator(),
                                                )
                                              : error != null
                                              ? Center(
                                                  child: Column(
                                                    mainAxisSize:
                                                        MainAxisSize.min,
                                                    children: [
                                                      Text(error!),
                                                      FilledButton(
                                                        onPressed: load,
                                                        child: const Text(
                                                          'Try again',
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                )
                                              : resolve(favorites).isEmpty
                                              ? Center(
                                                  child: TextButton.icon(
                                                    onPressed: openDrawer,
                                                    icon: const Icon(
                                                      Icons.add_rounded,
                                                    ),
                                                    label: const Text(
                                                      'Pin apps from your library',
                                                    ),
                                                  ),
                                                )
                                              : PageView.builder(
                                                  itemCount:
                                                      (resolve(
                                                                favorites,
                                                              ).length /
                                                              (columns * 2))
                                                          .ceil(),
                                                  itemBuilder: (context, page) {
                                                    final items =
                                                        resolve(favorites)
                                                            .skip(
                                                              page *
                                                                  columns *
                                                                  2,
                                                            )
                                                            .take(columns * 2)
                                                            .toList();
                                                    return GridView.builder(
                                                      padding: EdgeInsets.zero,
                                                      physics:
                                                          const NeverScrollableScrollPhysics(),
                                                      itemCount: items.length,
                                                      gridDelegate:
                                                          SliverGridDelegateWithFixedCrossAxisCount(
                                                            crossAxisCount:
                                                                columns,
                                                            mainAxisExtent: 104,
                                                          ),
                                                      itemBuilder:
                                                          (context, i) =>
                                                              appTile(
                                                                context,
                                                                items[i],
                                                              ),
                                                    );
                                                  },
                                                ),
                                        ),
                                        TextButton.icon(
                                          key: const Key('open-drawer'),
                                          onPressed: openDrawer,
                                          icon: const Icon(
                                            Icons.keyboard_arrow_up_rounded,
                                            size: 20,
                                          ),
                                          label: const Text(
                                            'App library',
                                            style: TextStyle(
                                              fontSize: 12,
                                              fontWeight: FontWeight.w500,
                                            ),
                                          ),
                                        ),
                                        SizedBox(
                                          height: 92,
                                          child: ClipRRect(
                                            borderRadius: BorderRadius.circular(
                                              32,
                                            ),
                                            child: Stack(
                                              children: [
                                                Positioned.fill(
                                                  child:
                                                      glass &&
                                                          defaultTargetPlatform ==
                                                              TargetPlatform
                                                                  .android
                                                      ? IgnorePointer(
                                                          child: AndroidView(
                                                            key: ValueKey(
                                                              wallpaper
                                                                  .hashCode,
                                                            ),
                                                            viewType:
                                                                'dev.sakura.home/glass',
                                                            layoutDirection:
                                                                TextDirection
                                                                    .ltr,
                                                          ),
                                                        )
                                                      : DecoratedBox(
                                                          decoration: BoxDecoration(
                                                            color:
                                                                Theme.of(
                                                                      context,
                                                                    )
                                                                    .colorScheme
                                                                    .surface
                                                                    .withValues(
                                                                      alpha:
                                                                          .75,
                                                                    ),
                                                            borderRadius:
                                                                BorderRadius.circular(
                                                                  32,
                                                                ),
                                                          ),
                                                        ),
                                                ),
                                                Positioned.fill(
                                                  child: Container(
                                                    decoration: BoxDecoration(
                                                      borderRadius:
                                                          BorderRadius.circular(
                                                            32,
                                                          ),
                                                      border: Border.all(
                                                        color: Colors.white
                                                            .withValues(
                                                              alpha: .5,
                                                            ),
                                                      ),
                                                    ),
                                                    child: Row(
                                                      mainAxisAlignment:
                                                          MainAxisAlignment
                                                              .spaceEvenly,
                                                      children: [
                                                        for (final app
                                                            in resolve(dock))
                                                          Expanded(
                                                            child: appTile(
                                                              context,
                                                              app,
                                                              showLabel: false,
                                                            ),
                                                          ),
                                                        if (resolve(
                                                          dock,
                                                        ).isEmpty)
                                                          TextButton.icon(
                                                            onPressed:
                                                                openDrawer,
                                                            icon: const Icon(
                                                              Icons.add_rounded,
                                                            ),
                                                            label: const Text(
                                                              'Choose dock apps',
                                                            ),
                                                          ),
                                                      ],
                                                    ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                          if (drawer.value > 0)
                            Positioned.fill(
                              child: GestureDetector(
                                onTap: closeDrawer,
                                child: ColoredBox(
                                  color: Colors.black.withValues(
                                    alpha: drawer.value * .12,
                                  ),
                                ),
                              ),
                            ),
                          if (drawer.value > 0)
                            Transform.translate(
                              offset: Offset(0, (1 - drawer.value) * h),
                              child: drawerContent(context, h, dark),
                            ),
                        ],
                      ),
                    );
                  },
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget drawerContent(
    BuildContext context,
    double height,
    bool dark,
  ) => SafeArea(
    bottom: false,
    child: Padding(
      padding: const EdgeInsets.only(top: 12),
      child: ClipRRect(
        borderRadius: const BorderRadius.vertical(top: Radius.circular(38)),
        child: BackdropFilter(
          filter: glass
              ? ui.ImageFilter.blur(sigmaX: 18, sigmaY: 18)
              : ui.ImageFilter.blur(sigmaX: 0, sigmaY: 0),
          child: ColoredBox(
            color: Theme.of(
              context,
            ).colorScheme.surface.withValues(alpha: glass ? .90 : 1),
            child: Column(
              children: [
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onVerticalDragUpdate: (d) {
                    searchFocus.unfocus();
                    drawer.value = (drawer.value - d.delta.dy / height).clamp(
                      0,
                      1,
                    );
                  },
                  onVerticalDragEnd: (d) => settle(
                    d.velocity.pixelsPerSecond.dy > 350 || drawer.value < .7
                        ? 0
                        : 1,
                    -d.velocity.pixelsPerSecond.dy / height,
                  ),
                  child: Column(
                    children: [
                      Container(
                        width: 36,
                        height: 4,
                        margin: const EdgeInsets.only(top: 12, bottom: 12),
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.outlineVariant,
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.fromLTRB(28, 6, 20, 14),
                        child: Row(
                          children: [
                            const Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    'Your library',
                                    style: TextStyle(
                                      fontSize: 30,
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: -1,
                                    ),
                                  ),
                                  Text(
                                    'Everything, beautifully within reach.',
                                    style: TextStyle(fontSize: 12),
                                  ),
                                ],
                              ),
                            ),
                            IconButton(
                              key: const Key('close-drawer'),
                              onPressed: closeDrawer,
                              tooltip: 'Close library',
                              icon: const Icon(Icons.close_rounded),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: TextField(
                    key: const Key('app-search'),
                    controller: search,
                    focusNode: searchFocus,
                    onChanged: filter,
                    textInputAction: TextInputAction.search,
                    decoration: InputDecoration(
                      hintText: 'Search your apps',
                      prefixIcon: const Icon(Icons.search_rounded),
                      suffixIcon: search.text.isEmpty
                          ? null
                          : IconButton(
                              onPressed: () {
                                search.clear();
                                filter('');
                              },
                              icon: const Icon(Icons.close_rounded),
                            ),
                      filled: true,
                      fillColor: Theme.of(context)
                          .colorScheme
                          .surfaceContainerHighest
                          .withValues(alpha: .65),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(22),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(28, 20, 28, 12),
                  child: Row(
                    children: [
                      Text(
                        search.text.isEmpty ? 'ALL APPS' : 'SEARCH RESULTS',
                        style: TextStyle(
                          fontSize: 10,
                          letterSpacing: 2,
                          fontWeight: FontWeight.w700,
                          color: Theme.of(context).colorScheme.primary,
                        ),
                      ),
                      const Spacer(),
                      Text(
                        '${results.length}',
                        style: const TextStyle(fontSize: 12),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: results.isEmpty
                      ? const Center(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.search_off_rounded, size: 42),
                              SizedBox(height: 12),
                              Text('No apps found'),
                              Text(
                                'Try another name',
                                style: TextStyle(fontSize: 12),
                              ),
                            ],
                          ),
                        )
                      : GridView.builder(
                          key: const Key('app-grid'),
                          padding: EdgeInsets.fromLTRB(
                            16,
                            0,
                            16,
                            24 + MediaQuery.of(context).padding.bottom,
                          ),
                          keyboardDismissBehavior:
                              ScrollViewKeyboardDismissBehavior.onDrag,
                          itemCount: results.length,
                          gridDelegate:
                              SliverGridDelegateWithFixedCrossAxisCount(
                                crossAxisCount: columns,
                                mainAxisExtent: 112,
                              ),
                          itemBuilder: (context, i) {
                            final start = math.min(i, 15) * .018;
                            final reveal =
                                ((drawer.value - .22 - start) /
                                        (1 - .22 - start))
                                    .clamp(0.0, 1.0);
                            return Opacity(
                              opacity: reveal,
                              child: Transform.translate(
                                offset: Offset(0, (1 - reveal) * 20),
                                child: appTile(
                                  context,
                                  results[i],
                                  library: true,
                                ),
                              ),
                            );
                          },
                        ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
  Widget appTile(
    BuildContext context,
    LaunchApp app, {
    bool showLabel = true,
    bool library = false,
  }) {
    if (iconCache.length > 256) iconCache.remove(iconCache.keys.first);
    final future = iconCache.putIfAbsent(
      app.id,
      () => host.icon(app.id).catchError((_) => null),
    );
    return PressScale(
      enabled: motion && !MediaQuery.of(context).disableAnimations,
      child: Semantics(
        label: app.label,
        button: true,
        child: InkWell(
          key: ValueKey('${library ? 'library' : 'home'}-${app.id}'),
          onTap: () => launch(app),
          onLongPress: () => appMenu(context, app),
          borderRadius: BorderRadius.circular(22),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(4, 9, 4, 4),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  width: 54,
                  height: 54,
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(18),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: .12),
                        blurRadius: 12,
                        offset: const Offset(0, 5),
                      ),
                    ],
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(18),
                    child: FutureBuilder<Uint8List?>(
                      future: future,
                      builder: (context, snapshot) =>
                          themedIcons && glyphKind(app.label) != null
                          ? ThemedAppIcon(kind: glyphKind(app.label)!)
                          : snapshot.data == null
                          ? CustomGlyph(label: app.label)
                          : Image.memory(
                              snapshot.data!,
                              fit: BoxFit.contain,
                              gaplessPlayback: true,
                            ),
                    ),
                  ),
                ),
                if (showLabel && labels) ...[
                  const SizedBox(height: 7),
                  Text(
                    app.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> appMenu(BuildContext context, LaunchApp app) async {
    HapticFeedback.mediumImpact();
    await showModalBottomSheet(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 18),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                title: Text(
                  app.label,
                  style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                subtitle: const Text('Make it yours'),
              ),
              ListTile(
                leading: Icon(
                  favorites.contains(app.id)
                      ? Icons.push_pin_outlined
                      : Icons.push_pin_rounded,
                ),
                title: Text(
                  favorites.contains(app.id)
                      ? 'Unpin from home'
                      : 'Pin to home',
                ),
                onTap: () {
                  setState(() {
                    favorites.contains(app.id)
                        ? favorites.remove(app.id)
                        : favorites.add(app.id);
                  });
                  persist();
                  Navigator.pop(ctx);
                },
              ),
              ListTile(
                leading: const Icon(Icons.dock_rounded),
                title: Text(
                  dock.contains(app.id)
                      ? 'Remove from dock'
                      : dock.length < 4
                      ? 'Add to dock'
                      : 'Replace last dock app',
                ),
                onTap: () {
                  setState(() {
                    if (dock.contains(app.id)) {
                      dock.remove(app.id);
                    } else {
                      if (dock.length >= 4) dock.removeLast();
                      dock.add(app.id);
                    }
                  });
                  persist();
                  Navigator.pop(ctx);
                },
              ),
              ListTile(
                leading: const Icon(Icons.info_outline_rounded),
                title: const Text('App information'),
                onTap: () {
                  Navigator.pop(ctx);
                  safeAction('details', app.id);
                },
              ),
              ListTile(
                leading: const Icon(Icons.delete_outline_rounded),
                title: const Text('Uninstall…'),
                onTap: () {
                  Navigator.pop(ctx);
                  safeAction('uninstall', app.id);
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> customize(BuildContext context) async {
    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) {
          void change(VoidCallback value) {
            setState(value);
            update(() {});
            persist();
          }

          return SafeArea(
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 28),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Make room for you.',
                      style: TextStyle(
                        fontSize: 28,
                        fontWeight: FontWeight.w600,
                        letterSpacing: -.8,
                      ),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      'Sakura Home · Material 3',
                      style: TextStyle(fontSize: 13),
                    ),
                    const SizedBox(height: 24),
                    const Text('Appearance'),
                    const SizedBox(height: 10),
                    SegmentedButton<String>(
                      segments: const [
                        ButtonSegment(
                          value: 'system',
                          icon: Icon(Icons.brightness_auto_rounded),
                          label: Text('Auto'),
                        ),
                        ButtonSegment(
                          value: 'light',
                          icon: Icon(Icons.light_mode_rounded),
                          label: Text('Light'),
                        ),
                        ButtonSegment(
                          value: 'dark',
                          icon: Icon(Icons.dark_mode_rounded),
                          label: Text('Dark'),
                        ),
                      ],
                      selected: {theme},
                      onSelectionChanged: (s) => change(() => theme = s.first),
                    ),
                    const SizedBox(height: 20),
                    Row(
                      children: [
                        const Expanded(child: Text('Grid columns')),
                        SegmentedButton<int>(
                          segments: const [
                            ButtonSegment(value: 4, label: Text('4')),
                            ButtonSegment(value: 5, label: Text('5')),
                          ],
                          selected: {columns},
                          onSelectionChanged: (s) =>
                              change(() => columns = s.first),
                        ),
                      ],
                    ),
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Sakura icons'),
                      subtitle: const Text('Original glyphs for familiar apps'),
                      value: themedIcons,
                      onChanged: (v) => change(() => themedIcons = v),
                    ),
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('App labels'),
                      value: labels,
                      onChanged: (v) => change(() => labels = v),
                    ),
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Fluid motion'),
                      subtitle: const Text(
                        'Spring drawer and tactile icon feedback',
                      ),
                      value: motion,
                      onChanged: (v) => change(() => motion = v),
                    ),
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Liquid glass'),
                      subtitle: const Text(
                        'Native refractive dock · frosted library',
                      ),
                      value: glass,
                      onChanged: (v) => change(() => glass = v),
                    ),
                    const Divider(),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.wallpaper_rounded),
                      title: const Text('Choose wallpaper'),
                      subtitle: const Text('Only the image you select is read'),
                      onTap: () async {
                        Navigator.pop(ctx);
                        try {
                          final changed = await LauncherHost.channel
                              .invokeMethod<bool>('wallpaper');
                          if (changed == true) await load();
                        } catch (e) {
                          if (context.mounted)
                            ScaffoldMessenger.of(
                              context,
                            ).showSnackBar(SnackBar(content: Text('$e')));
                        }
                      },
                    ),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.home_outlined),
                      title: const Text('Set as default launcher'),
                      subtitle: const Text('Choose in Android settings'),
                      onTap: () {
                        Navigator.pop(ctx);
                        safeAction('defaultLauncher');
                      },
                    ),
                    const SizedBox(height: 12),
                    TextButton(
                      onPressed: () => showLicensePage(
                        context: context,
                        applicationName: 'Sakura Home',
                        applicationVersion: '0.1.3',
                      ),
                      child: const Text('Open-source licenses'),
                    ),
                    Text(
                      'v0.1.3 · Flutter + Kotlin + ${rust ? 'Rust' : 'fallback search'}\nNo root or APatch module required.',
                      style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class PressScale extends StatefulWidget {
  const PressScale({super.key, required this.child, required this.enabled});
  final Widget child;
  final bool enabled;
  @override
  State<PressScale> createState() => _PressScaleState();
}

class _PressScaleState extends State<PressScale> {
  bool pressed = false;
  @override
  Widget build(BuildContext context) => Listener(
    onPointerDown: (_) => setState(() => pressed = true),
    onPointerUp: (_) => setState(() => pressed = false),
    onPointerCancel: (_) => setState(() => pressed = false),
    child: AnimatedScale(
      scale: pressed && widget.enabled ? .91 : 1,
      duration: Duration(milliseconds: widget.enabled ? 180 : 0),
      curve: Curves.easeOutCubic,
      child: widget.child,
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

class CustomGlyph extends StatelessWidget {
  const CustomGlyph({super.key, required this.label});
  final String label;
  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      gradient: LinearGradient(
        colors: [
          Color(0xff9b88c4 + (label.hashCode.abs() % 20) * 256),
          const Color(0xff5d608f),
        ],
      ),
    ),
    child: const Center(child: SakuraMark(size: 28)),
  );
}

String? glyphKind(String label) {
  final value = label.toLowerCase();
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
