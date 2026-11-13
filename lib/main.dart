import 'dart:convert';

// Keep the Flutter SDK Material library while it remains bundled.
// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

import 'editor_native.dart' if (dart.library.js_interop) 'editor_web.dart';

final packagePattern = RegExp(r'^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$');
const native = MethodChannel('nexus/native');

void main() => runApp(const NexusApp());

class NexusApp extends StatelessWidget {
  const NexusApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    theme: ThemeData.dark(useMaterial3: true).copyWith(
      scaffoldBackgroundColor: const Color(0xff0d1117),
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xff63c6ff),
        brightness: Brightness.dark,
      ),
    ),
    home: const NexusScreen(),
  );
}

class NexusScreen extends StatefulWidget {
  const NexusScreen({super.key});

  @override
  State<NexusScreen> createState() => _NexusScreenState();
}

class _NexusScreenState extends State<NexusScreen> {
  bool apkDialogOpen = false;

  @override
  void initState() {
    super.initState();
    if (!kIsWeb) native.setMethodCallHandler(_handleNative);
  }

  @override
  void dispose() {
    if (!kIsWeb) native.setMethodCallHandler(null);
    super.dispose();
  }

  Future<void> _handleNative(MethodCall call) async {
    if (!mounted) return;
    if (call.method != 'message') return;
    try {
      final message =
          jsonDecode(call.arguments as String) as Map<String, dynamic>;
      switch (message['kind']) {
        case 'save':
          final bytes = base64Decode(message['data'] as String);
          final filename = message['filename'] as String;
          if (await saveDocument(filename, message['mime'] as String, bytes)) {
            if (mounted) _notice('Saved $filename');
          }
        case 'apk':
          if (apkDialogOpen) return;
          apkDialogOpen = true;
          try {
            await showDialog<void>(
              context: context,
              barrierDismissible: false,
              builder: (_) => ApkDialog(
                game: message['html'] as String,
                projectName: message['name'] as String,
                orientation: message['orientation'] as String?,
              ),
            );
          } finally {
            apkDialogOpen = false;
          }
      }
    } catch (error) {
      if (mounted)
        _notice(
          error is PlatformException
              ? error.message ?? 'Android could not open the file picker.'
              : 'The export could not be processed.',
        );
    }
  }

  void _notice(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  @override
  Widget build(BuildContext context) {
    if (kIsWeb) return buildWebEditor();
    const web = AndroidView(
      viewType: 'nexus/web',
      layoutDirection: TextDirection.ltr,
    );
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (!didPop && (await native.invokeMethod<bool>('webBack') != true)) {
          await native.invokeMethod<void>('exit');
        }
      },
      child: Scaffold(
        resizeToAvoidBottomInset: true,
        body: const SafeArea(child: web),
      ),
    );
  }
}

Future<bool> saveDocument(String name, String mime, Uint8List bytes) async =>
    await native.invokeMethod<bool>('save', {
      'name': name,
      'mime': mime,
      'bytes': bytes,
    }) ==
    true;

class ApkDialog extends StatefulWidget {
  const ApkDialog({
    super.key,
    required this.game,
    required this.projectName,
    this.orientation,
  });
  final String game;
  final String projectName;
  final String? orientation;

  @override
  State<ApkDialog> createState() => _ApkDialogState();
}

class _ApkDialogState extends State<ApkDialog> {
  final form = GlobalKey<FormState>();
  final package = TextEditingController();
  final version = TextEditingController(text: '1.0.0');
  final buildNumber = TextEditingController(text: '1');
  late final TextEditingController title;
  late String orientation;
  String status = '';
  bool busy = false;
  Uint8List? apk;
  Uint8List? icon;
  Uint8List? signingKey;
  final keyPassword = TextEditingController();
  final keyAlias = TextEditingController();

  @override
  void initState() {
    super.initState();
    title = TextEditingController(text: widget.projectName);
    orientation = switch (widget.orientation) {
      'portrait' => 'portrait',
      'landscape' => 'landscape',
      _ => 'auto',
    };
  }

  @override
  void dispose() {
    for (final input in [
      package,
      version,
      buildNumber,
      title,
      keyPassword,
      keyAlias,
    ]) {
      input.dispose();
    }
    super.dispose();
  }

  Future<void> buildApk() async {
    if (!form.currentState!.validate()) return;
    setState(() {
      busy = true;
      apk = null;
      status = 'Packaging and signing on this device…';
    });
    try {
      final result = await native.invokeMethod<Uint8List>('buildApk', {
        'html': widget.game,
        'package': package.text.trim(),
        'name': title.text.trim(),
        'orientation': orientation,
        'version': version.text.trim(),
        'number': int.parse(buildNumber.text.trim()),
        'icon': icon,
        'keystore': signingKey,
        'keyPassword': keyPassword.text,
        'keyAlias': keyAlias.text.trim(),
      });
      if (mounted)
        setState(() {
          apk = result;
          status = result == null
              ? 'No APK was generated.'
              : 'Your APK is ready to save.';
        });
    } on PlatformException catch (error) {
      if (mounted)
        setState(() => status = error.message ?? 'Could not build the APK.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> pickSigningKey() async {
    try {
      final bytes = await native.invokeMethod<Uint8List>('chooseSigningKey');
      if (bytes != null && mounted) setState(() => signingKey = bytes);
    } on PlatformException catch (error) {
      if (mounted)
        setState(() => status = error.message ?? 'Could not read signing key.');
    }
  }

  Future<void> pickIcon() async {
    try {
      final bytes = await native.invokeMethod<Uint8List>('icon');
      if (bytes != null && mounted) setState(() => icon = bytes);
    } on PlatformException catch (error) {
      if (mounted)
        setState(() => status = error.message ?? 'Could not read icon.');
    }
  }

  Future<void> saveApk() async {
    final data = apk;
    if (data == null) return;
    setState(() => busy = true);
    try {
      final saved = await saveDocument(
        '${package.text.trim()}.apk',
        'application/vnd.android.package-archive',
        data,
      );
      if (mounted && saved) setState(() => status = 'APK saved.');
    } on PlatformException catch (error) {
      if (mounted)
        setState(() => status = error.message ?? 'Could not save the APK.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Widget input(
    TextEditingController controller,
    String label, {
    required String? Function(String) validate,
    String? hint,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: TextFormField(
      controller: controller,
      enabled: !busy && apk == null,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        border: const OutlineInputBorder(),
      ),
      autocorrect: false,
      validator: (value) => validate(value?.trim() ?? ''),
    ),
  );

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Export Android APK'),
    scrollable: true,
    content: SizedBox(
      width: 440,
      child: Form(
        key: form,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Creates and signs a standalone game APK on this device.',
            ),
            const SizedBox(height: 16),
            input(
              title,
              'App name',
              validate: (v) =>
                  v.isEmpty ||
                      v.length > 60 ||
                      v.contains(RegExp(r'[\x00-\x1f<>]'))
                  ? 'Enter a valid name up to 60 characters.'
                  : null,
            ),
            input(
              package,
              'Package name',
              hint: 'com.example.mygame',
              validate: (v) =>
                  !packagePattern.hasMatch(v) ||
                      v.length > 120 ||
                      (v == 'com.nexusengine.app' ||
                          v == 'com.nexusengine.templateplaceholder')
                  ? 'Enter a unique ID such as com.example.mygame.'
                  : null,
            ),
            OutlinedButton.icon(
              onPressed: busy || apk != null
                  ? null
                  : () async {
                      final choice = await showModalBottomSheet<String>(
                        context: context,
                        builder: (context) => SafeArea(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              for (final entry in const {
                                'auto': 'Follow device',
                                'portrait': 'Portrait',
                                'landscape': 'Landscape',
                              }.entries)
                                ListTile(
                                  leading: const Icon(
                                    Icons.screen_rotation_outlined,
                                  ),
                                  title: Text(entry.value),
                                  selected: orientation == entry.key,
                                  trailing: orientation == entry.key
                                      ? const Icon(Icons.check)
                                      : null,
                                  onTap: () =>
                                      Navigator.pop(context, entry.key),
                                ),
                            ],
                          ),
                        ),
                      );
                      if (choice != null && mounted)
                        setState(() => orientation = choice);
                    },
              icon: const Icon(Icons.screen_rotation_outlined),
              label: Text(
                'Orientation · ${const {'auto': 'Follow device', 'portrait': 'Portrait', 'landscape': 'Landscape'}[orientation]}',
              ),
            ),
            const SizedBox(height: 12),
            input(
              version,
              'Version',
              validate: (v) =>
                  !RegExp(r'^\d{1,6}\.\d{1,6}\.\d{1,6}$').hasMatch(v)
                  ? 'Use a version such as 1.0.0.'
                  : null,
            ),
            input(
              buildNumber,
              'Build number',
              validate: (v) {
                final number = int.tryParse(v);
                return !RegExp(r'^[1-9]\d{0,8}$').hasMatch(v) || number == null
                    ? 'Enter a positive build number (up to 9 digits).'
                    : null;
              },
            ),
            Row(
              children: [
                if (icon != null)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Image.memory(
                      icon!,
                      width: 36,
                      height: 36,
                      fit: BoxFit.contain,
                    ),
                  ),
                OutlinedButton.icon(
                  onPressed: busy || apk != null ? null : pickIcon,
                  icon: const Icon(Icons.image_outlined),
                  label: Text(icon == null ? 'Choose app icon' : 'Change icon'),
                ),
                if (icon != null)
                  IconButton(
                    tooltip: 'Remove custom icon',
                    onPressed: busy || apk != null
                        ? null
                        : () => setState(() => icon = null),
                    icon: const Icon(Icons.close),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            const SizedBox(height: 10),
            OutlinedButton.icon(
              onPressed: busy || apk != null ? null : pickSigningKey,
              icon: const Icon(Icons.key_outlined),
              label: Text(
                signingKey == null
                    ? 'Use portable signing key (.p12)'
                    : 'Change portable signing key',
              ),
            ),
            if (signingKey != null) ...[
              TextField(
                controller: keyPassword,
                obscureText: true,
                enabled: !busy && apk == null,
                decoration: const InputDecoration(labelText: 'Key password'),
              ),
              TextField(
                controller: keyAlias,
                enabled: !busy && apk == null,
                decoration: const InputDecoration(
                  labelText: 'Key alias (optional with one key)',
                ),
              ),
              TextButton(
                onPressed: busy || apk != null
                    ? null
                    : () => setState(() {
                        signingKey = null;
                        keyPassword.clear();
                        keyAlias.clear();
                      }),
                child: const Text('Use device signing key'),
              ),
            ],
            if (busy) const LinearProgressIndicator(),
            if (status.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(status),
              ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: busy ? null : () => Navigator.of(context).pop(),
        child: const Text('Close'),
      ),
      FilledButton(
        onPressed: busy
            ? null
            : apk == null
            ? buildApk
            : saveApk,
        child: Text(apk == null ? 'Build APK' : 'Save APK'),
      ),
    ],
  );
}
