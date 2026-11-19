import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const IsleApp());

const ink = Color(0xFF111312);
const surface = Color(0xFF1C1F1D);
const accent = Color(0xFFBDF5CE);
const secondary = Color(0xFFA7ADA9);

class IsleApp extends StatelessWidget {
  const IsleApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Isle',
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: ink,
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: accent,
        brightness: Brightness.dark,
        surface: surface,
        primary: accent,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: ink,
        surfaceTintColor: Colors.transparent,
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 52),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
      dividerTheme: const DividerThemeData(color: Color(0xFF303530), space: 28),
    ),
    home: const IsleHome(),
  );
}

class IsleHome extends StatefulWidget {
  const IsleHome({super.key});
  @override
  State<IsleHome> createState() => _IsleHomeState();
}

class _IsleHomeState extends State<IsleHome> with WidgetsBindingObserver {
  static const channel = MethodChannel('dev.isle/app');
  Map<String, dynamic> status = {};
  Map<String, dynamic> settings = {};
  bool busy = false;
  bool loading = true;
  String? error;
  int page = 0;
  Timer? timer;
  bool refreshing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    poll();
  }

  void poll() {
    timer?.cancel();
    timer = Timer.periodic(const Duration(seconds: 2), (_) => refresh());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      refresh();
      poll();
    } else {
      timer?.cancel();
    }
  }

  Future<void> refresh() async {
    if (refreshing) return;
    refreshing = true;
    try {
      final result = await channel.invokeMapMethod<String, dynamic>('status');
      if (!mounted) return;
      setState(() {
        status = result ?? {};
        if (loading) {
          settings = Map<String, dynamic>.from(
            status['settings'] as Map? ?? defaults,
          );
        }
        loading = false;
      });
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          error = e.message;
          loading = false;
        });
      }
    } finally {
      refreshing = false;
    }
  }

  static const defaults = <String, dynamic>{
    'width': 110,
    'height': 34,
    'offset': 0,
    'horizontal': 0,
    'touchExtension': 30,
    'alwaysVisible': true,
    'charging': true,
    'media': true,
    'notifications': false,
    'haptics': true,
    'reducedMotion': false,
    'outline': false,
    'autoHide': false,
    'timeout': 5,
    'hideApps': 'com.google.android.apps.nbu.paisa.user,com.phonepe.app,net.one97.paytm',
    'mutedApps': '',
    'fontStyle': 'ntype',
    'glassmorphism': false,
    'dynamicAlbumColor': true,
  };
  Future<void> action(String method) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await channel.invokeMethod<void>(method);
      await refresh();
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => error = e.message ?? 'Could not complete the action.');
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> save(String key, dynamic value) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final saved = await channel.invokeMapMethod<String, dynamic>('save', {
        key: value,
      });
      if (mounted) {
        setState(() => settings = Map<String, dynamic>.from(saved ?? settings));
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => error = e.message ?? 'Could not save settings.');
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  bool flag(String key) => settings[key] == true;
  int number(String key) =>
      (settings[key] as num? ?? defaults[key] as num).round();
  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final running = status['running'] == true;
    final paused = status['paused'] == true;
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'isle',
          style: TextStyle(
            fontSize: 28,
            fontWeight: FontWeight.w600,
            letterSpacing: -1.5,
          ),
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 24),
            child: Center(
              child: Text(
                running
                    ? (paused ? 'PAUSED' : 'CONNECTED')
                    : 'READY WHEN YOU ARE',
                style: TextStyle(
                  fontSize: 9,
                  letterSpacing: 1.6,
                  color: running && !paused ? accent : secondary,
                ),
              ),
            ),
          ),
        ],
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 620),
                child: ListView(
                  key: ValueKey(page),
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
                  children: [
                    if (error != null ||
                        (status['error'] as String? ?? '').isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: Text(
                          error ?? status['error'] as String,
                          style: const TextStyle(color: Color(0xFFFFB4A9)),
                        ),
                      ),
                    ...switch (page) {
                      0 => home(),
                      1 => appearance(),
                      _ => access(),
                    },
                  ],
                ),
              ),
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: page,
        onDestinationSelected: (v) => setState(() => page = v),
        backgroundColor: ink,
        indicatorColor: const Color(0xFF2A3D30),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.blur_on), label: 'Island'),
          NavigationDestination(icon: Icon(Icons.tune), label: 'Appearance'),
          NavigationDestination(
            icon: Icon(Icons.shield_outlined),
            label: 'Access',
          ),
        ],
      ),
    );
  }

  List<Widget> home() {
    final running = status['running'] == true;
    final permission = status['permission'] == true;
    final paused = status['paused'] == true;
    return [
      const Text(
        'A little space.\nFor what’s happening.',
        style: TextStyle(fontSize: 32, height: 1.13, letterSpacing: -1.2),
      ),
      const SizedBox(height: 24),
      Container(
        height: 178,
        decoration: BoxDecoration(
          color: const Color(0xFF202823),
          borderRadius: BorderRadius.circular(28),
        ),
        child: Stack(
          alignment: Alignment.center,
          children: [
            Positioned(
              top: 26,
              child: Container(
                width: 110,
                height: 34,
                decoration: BoxDecoration(
                  color: Colors.black,
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Align(
                  alignment: Alignment.centerRight,
                  child: Padding(
                    padding: const EdgeInsets.only(right: 12),
                    child: Container(
                      width: 5,
                      height: 5,
                      decoration: const BoxDecoration(
                        color: accent,
                        shape: BoxShape.circle,
                      ),
                    ),
                  ),
                ),
              ),
            ),
            const Positioned(
              top: 80,
              child: Text(
                'Music. Power. A moment to catch up.',
                style: TextStyle(color: Color(0xFFCED9D0), fontSize: 13),
              ),
            ),
            Positioned(
              bottom: 23,
              child: Text(
                running
                    ? (paused
                          ? 'Paused · your screen is clear'
                          : 'Tap just below your camera')
                    : 'Made for your Nothing Phone 2a',
                style: const TextStyle(color: secondary, fontSize: 11),
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 20),
      if (!permission) ...[
        FilledButton.icon(
          onPressed: busy ? null : () => action('permission'),
          icon: const Icon(Icons.layers_outlined),
          label: const Text('Allow display over other apps'),
        ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 12),
          child: Text(
            'One permission to get started. Music and notifications are optional.',
            style: TextStyle(color: secondary, height: 1.5),
          ),
        ),
      ],
      FilledButton.icon(
        onPressed: busy || !permission
            ? null
            : () => action(running ? 'pause' : 'start'),
        icon: Icon(running && !paused ? Icons.pause : Icons.play_arrow),
        label: Text(
          running ? (paused ? 'Resume Isle' : 'Pause Isle') : 'Turn on Isle',
        ),
      ),
      if (running)
        Row(
          children: [
            Expanded(
              child: TextButton(
                onPressed: busy || paused ? null : () => action('preview'),
                child: const Text('Open island'),
              ),
            ),
            Expanded(
              child: TextButton(
                onPressed: busy ? null : () => action('stop'),
                child: const Text('Turn off'),
              ),
            ),
          ],
        ),
      if (status['suppressed'] == true && !paused)
        const Padding(
          padding: EdgeInsets.only(top: 8),
          child: Text(
            'Hidden by your screen or app rules.',
            style: TextStyle(color: secondary),
          ),
        ),
      const SizedBox(height: 24),
      label('LIVE ACTIVITIES'),
      card([
        toggle(
          'Charging',
          'A brief arrival when you plug in.',
          'charging',
          Icons.bolt,
        ),
        const Divider(),
        toggle(
          'Media controls',
          'Your current track, play, pause and skip.',
          'media',
          Icons.music_note_outlined,
        ),
        if (flag('media') && status['notificationAccess'] != true)
          permissionLink(),
        const Divider(),
        toggle(
          'Notification peeks',
          'Brief previews. Off until you choose.',
          'notifications',
          Icons.chat_bubble_outline,
        ),
        if (flag('notifications') && status['notificationAccess'] != true)
          permissionLink(),
        if (flag('notifications'))
          TextButton(
            onPressed: busy
                ? null
                : () => pickApps('mutedApps', 'Mute notifications from'),
            child: const Text('Choose muted apps'),
          ),
      ]),
      const SizedBox(height: 20),
      label('OUT OF YOUR WAY'),
      card([
        toggle(
          'Hide in selected apps',
          'Uses usage access. Detection can take about a second or longer.',
          'autoHide',
          Icons.visibility_off_outlined,
        ),
        if (flag('autoHide') && status['usageAccess'] != true)
          TextButton(
            onPressed: busy ? null : () => action('usageAccess'),
            child: const Text('Grant usage access'),
          ),
        TextButton(
          onPressed: busy ? null : () => pickApps('hideApps', 'Hide Isle in'),
          child: const Text('Choose hidden apps'),
        ),
        const Text(
          'Always hidden while locked and in landscape. Pause Isle for payment flows; app detection is best effort.',
          style: TextStyle(fontSize: 12, color: secondary, height: 1.5),
        ),
      ]),
    ];
  }

  List<Widget> appearance() => [
    const Text(
      'Fits like it belongs.',
      style: TextStyle(fontSize: 30, letterSpacing: -1),
    ),
    const SizedBox(height: 10),
    const Text(
      'Your 110 × 34 dp fit is the default. Changes are saved and applied to the island.',
      style: TextStyle(color: secondary, height: 1.5),
    ),
    const SizedBox(height: 24),
    card([
      toggle(
        'Always show the pill',
        'Turn off for a transparent idle touch area.',
        'alwaysVisible',
        Icons.remove,
      ),
      const Divider(),
      toggle(
        'Tinted obsidian glass',
        'Polished dark obsidian glass with specular sheen and rim highlights.',
        'glassmorphism',
        Icons.blur_on_rounded,
      ),
      const Divider(),
      toggle(
        'Dynamic album tint',
        'Tints the music player glass with colors sampled from album art.',
        'dynamicAlbumColor',
        Icons.palette_outlined,
      ),
      const Divider(),
      toggle(
        'Gentle motion',
        'Fade between states without the spring.',
        'reducedMotion',
        Icons.motion_photos_off_outlined,
      ),
      const Divider(),
      toggle(
        'Haptic feedback',
        'A small tick when you open the island.',
        'haptics',
        Icons.vibration,
      ),
      const Divider(),
      slider('Close after', 'timeout', 3, 15, unit: 's'),
    ]),
    const SizedBox(height: 20),
    label('TYPOGRAPHY & FONT'),
    card([
      fontOption('Nothing NType82', 'Nothing OS signature clean sans-serif', 'ntype'),
      const Divider(),
      fontOption('Instrument Sans', 'Modern expressive editorial geometric sans', 'instrument'),
      const Divider(),
      fontOption('Inter', 'Neutral, highly legible grotesque sans', 'inter'),
      const Divider(),
      fontOption('Nothing NDot', 'Retro dot-matrix typography', 'ndot'),
      const Divider(),
      fontOption('System Default', 'Standard Android system font', 'system'),
    ]),
    const SizedBox(height: 20),
    label('CAMERA FIT'),
    card([
      slider('Width', 'width', 16, 220),
      slider('Height', 'height', 8, 80),
      slider('Horizontal position', 'horizontal', -80, 80),
      slider('Vertical position', 'offset', -40, 160),
      slider('Touch extension below', 'touchExtension', 0, 80),
      const Text(
        'The extension catches taps below the status bar without moving the pill. It consumes touches in that small area.',
        style: TextStyle(color: secondary, fontSize: 12, height: 1.5),
      ),
      const Divider(),
      toggle(
        'Show calibration guides',
        'Red: pill. Green: touch area.',
        'outline',
        Icons.crop_free,
      ),
    ]),
    const SizedBox(height: 16),
    OutlinedButton(
      onPressed: busy ? null : resetFit,
      child: const Text('Restore your 110 × 34 fit'),
    ),
  ];
  List<Widget> access() => [
    const Text(
      'You’re in control.',
      style: TextStyle(fontSize: 30, letterSpacing: -1),
    ),
    const SizedBox(height: 10),
    const Text(
      'Each permission has a job. Only display over other apps is needed to turn on the island.',
      style: TextStyle(color: secondary, height: 1.5),
    ),
    const SizedBox(height: 24),
    card([
      permissionRow(
        'Display over other apps',
        'Place the island around your camera.',
        'permission',
        'permission',
      ),
      const Divider(),
      permissionRow(
        'Media & notification access',
        'Read media sessions and, if enabled, notification previews. Android may redact sensitive content.',
        'notificationAccess',
        'notificationAccess',
      ),
      const Divider(),
      permissionRow(
        'Control notification',
        'Pause or stop Isle from your notification shade.',
        'notificationPermission',
        'notifications',
      ),
      const Divider(),
      permissionRow(
        'Usage access',
        'Optional. Check which app is open for your hide list.',
        'usageAccess',
        'usageAccess',
      ),
      const Divider(),
      permissionRow(
        'Battery optimization',
        'Optional. If Nothing OS stops Isle, allow unrestricted battery use in Android settings.',
        'batteryExempt',
        'batterySettings',
      ),
    ]),
    const SizedBox(height: 20),
    card([
      const Text('On your phone. Only.', style: TextStyle(fontSize: 18)),
      const SizedBox(height: 8),
      const Text(
        'Isle does not upload notification text or media details. Previews stay in memory and disappear after a few seconds. No accessibility service is used.',
        style: TextStyle(color: secondary, height: 1.5),
      ),
      const SizedBox(height: 16),
      const Text(
        'ISLE  /  0.1.0',
        style: TextStyle(color: secondary, fontSize: 11, letterSpacing: 2),
      ),
      TextButton.icon(
        onPressed: copyDiagnostics,
        icon: const Icon(Icons.copy, size: 16),
        label: const Text('Copy diagnostics'),
      ),
      if (status['notificationAccess'] == true &&
          status['listenerConnected'] != true)
        const Text(
          'Waiting for Android to connect the notification listener. If it stays disconnected, toggle access off and on.',
          style: TextStyle(color: secondary, fontSize: 12),
        ),
    ]),
  ];
  Widget label(String text) => Padding(
    padding: const EdgeInsets.only(left: 4, bottom: 12),
    child: Text(
      text,
      style: const TextStyle(
        fontSize: 10,
        color: secondary,
        letterSpacing: 1.8,
      ),
    ),
  );
  Widget card(List<Widget> children) => Card(
    margin: EdgeInsets.zero,
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    ),
  );
  Widget toggle(String title, String description, String key, IconData icon) =>
      Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Icon(icon, size: 21, color: secondary),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: const TextStyle(fontSize: 15)),
                const SizedBox(height: 4),
                Text(
                  description,
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.4,
                    color: secondary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Switch(
            value: flag(key),
            onChanged: busy ? null : (value) => save(key, value),
          ),
        ],
      );
  Widget fontOption(String name, String desc, String value) {
    final selected = (settings['fontStyle'] as String? ?? 'ntype') == value;
    return InkWell(
      onTap: busy ? null : () => save('fontStyle', value),
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: selected ? FontWeight.bold : FontWeight.w500,
                      color: selected ? accent : Colors.white,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    desc,
                    style: const TextStyle(fontSize: 12, color: secondary),
                  ),
                ],
              ),
            ),
            Icon(
              selected ? Icons.radio_button_checked : Icons.radio_button_off,
              color: selected ? accent : secondary,
              size: 20,
            ),
          ],
        ),
      ),
    );
  }
  Widget permissionLink() => TextButton(
    onPressed: busy ? null : () => action('notificationAccess'),
    child: const Text('Connect notification access'),
  );
  Widget permissionRow(
    String title,
    String detail,
    String key,
    String method,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Expanded(child: Text(title, style: const TextStyle(fontSize: 16))),
          Icon(
            status[key] == true ? Icons.check_circle : Icons.circle_outlined,
            color: status[key] == true ? accent : secondary,
            size: 20,
          ),
        ],
      ),
      const SizedBox(height: 6),
      Text(
        detail,
        style: const TextStyle(color: secondary, fontSize: 12, height: 1.5),
      ),
      TextButton(
        onPressed: busy ? null : () => action(method),
        child: Text(status[key] == true ? 'Manage' : 'Allow'),
      ),
    ],
  );
  Widget slider(
    String title,
    String key,
    int min,
    int max, {
    String unit = 'dp',
  }) {
    final value = number(key);
    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '$title · $value $unit',
                style: const TextStyle(fontSize: 13),
              ),
            ),
            IconButton(
              tooltip: 'Decrease $title',
              onPressed: busy || value <= min
                  ? null
                  : () => save(key, value - 1),
              icon: const Icon(Icons.remove, size: 18),
            ),
            IconButton(
              tooltip: 'Increase $title',
              onPressed: busy || value >= max
                  ? null
                  : () => save(key, value + 1),
              icon: const Icon(Icons.add, size: 18),
            ),
          ],
        ),
        Slider(
          value: value.toDouble().clamp(min.toDouble(), max.toDouble()),
          min: min.toDouble(),
          max: max.toDouble(),
          divisions: max - min,
          onChanged: busy
              ? null
              : (v) => setState(() => settings[key] = v.round()),
          onChangeEnd: busy ? null : (v) => save(key, v.round()),
        ),
      ],
    );
  }

  Future<void> resetFit() async {
    setState(() => busy = true);
    try {
      final result = await channel.invokeMapMethod<String, dynamic>('save', {
        'width': 110,
        'height': 34,
        'offset': 0,
        'horizontal': 0,
        'touchExtension': 30,
        'outline': false,
      });
      if (mounted) {
        setState(
          () => settings = Map<String, dynamic>.from(result ?? settings),
        );
      }
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> copyDiagnostics() async {
    final text = status.entries
        .where((e) => e.key != 'settings')
        .map((e) => '${e.key}: ${e.value}')
        .join('\n');
    await Clipboard.setData(
      ClipboardData(text: 'Isle\n$text\nSettings: $settings'),
    );
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Diagnostics copied')));
    }
  }

  Future<void> pickApps(String key, String title) async {
    try {
      final apps = await channel.invokeListMethod<dynamic>('apps') ?? [];
      if (!mounted) return;
      final selected = (settings[key] as String? ?? '')
          .split(RegExp(r'[,\n]'))
          .map((s) => s.trim())
          .where((s) => s.isNotEmpty)
          .toSet();
      final result = await showModalBottomSheet<Set<String>>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        builder: (_) => AppPicker(
          title: title,
          apps: apps.map((a) => Map<String, dynamic>.from(a as Map)).toList(),
          selected: selected,
        ),
      );
      if (result != null && mounted) await save(key, result.join(','));
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message);
    }
  }
}

class AppPicker extends StatefulWidget {
  const AppPicker({
    super.key,
    required this.title,
    required this.apps,
    required this.selected,
  });
  final String title;
  final List<Map<String, dynamic>> apps;
  final Set<String> selected;
  @override
  State<AppPicker> createState() => _AppPickerState();
}

class _AppPickerState extends State<AppPicker> {
  late final selected = {...widget.selected};
  String search = '';
  final packageInput = TextEditingController();
  @override
  void dispose() {
    packageInput.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final installed = widget.apps.map((a) => a['package']).toSet();
    final apps =
        [
              ...widget.apps,
              ...selected
                  .where((p) => !installed.contains(p))
                  .map((p) => {'name': p, 'package': p}),
            ]
            .where(
              (a) => '${a['name']} ${a['package']}'.toLowerCase().contains(
                search.toLowerCase(),
              ),
            )
            .toList();
    return SizedBox(
      height: MediaQuery.sizeOf(context).height * .85,
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          20,
          20,
          20,
          MediaQuery.viewInsetsOf(context).bottom + 12,
        ),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    widget.title,
                    style: const TextStyle(fontSize: 21),
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, selected),
                  child: const Text('Save'),
                ),
              ],
            ),
            TextField(
              decoration: const InputDecoration(
                hintText: 'Search apps',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: (v) => setState(() => search = v),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: ListView.builder(
                itemCount: apps.length,
                itemBuilder: (context, i) {
                  final p = apps[i]['package'] as String;
                  return CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(apps[i]['name'] as String),
                    subtitle: Text(p, style: const TextStyle(fontSize: 10)),
                    value: selected.contains(p),
                    onChanged: (v) => setState(() {
                      if (v == true) {
                        selected.add(p);
                      } else {
                        selected.remove(p);
                      }
                    }),
                  );
                },
              ),
            ),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: packageInput,
                    decoration: const InputDecoration(
                      hintText: 'Or enter a package name',
                    ),
                    autocorrect: false,
                  ),
                ),
                IconButton(
                  tooltip: 'Add package',
                  onPressed: () {
                    final p = packageInput.text.trim();
                    if (RegExp(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
                        .hasMatch(p)) {
                      setState(() {
                        selected.add(p);
                        search = '';
                        packageInput.clear();
                      });
                    }
                  },
                  icon: const Icon(Icons.add),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
