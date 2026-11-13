import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'github_build.dart';

const player = bool.fromEnvironment('NEXUS_GAME');
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
  bool immersive = player;
  bool apkDialogOpen = false;

  @override
  void initState() {
    super.initState();
    native.setMethodCallHandler(_handleNative);
  }

  @override
  void dispose() {
    native.setMethodCallHandler(null);
    super.dispose();
  }

  Future<void> _handleNative(MethodCall call) async {
    if (!mounted) return;
    if (call.method == 'immersive') {
      setState(() => immersive = call.arguments == true);
      return;
    }
    if (call.method != 'message' || player) return;
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
        case 'fullscreen':
          await native.invokeMethod<void>('fullscreen');
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
    const web = AndroidView(
      viewType: 'nexus/web',
      layoutDirection: TextDirection.ltr,
    );
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (!didPop &&
            (player || await native.invokeMethod<bool>('webBack') != true)) {
          await native.invokeMethod<void>('exit');
        }
      },
      child: Scaffold(
        resizeToAvoidBottomInset: !player,
        body: SafeArea(
          top: !immersive,
          bottom: !immersive,
          left: !immersive,
          right: !immersive,
          child: web,
        ),
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
  final token = TextEditingController();
  final version = TextEditingController(text: '1.0.0');
  final buildNumber = TextEditingController(text: '1');
  late final TextEditingController title;
  late String orientation;
  String status = '';
  int? run;
  bool busy = false;
  Uint8List? apk;
  Uint8List? icon;
  GitHubBuild? client;

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
    client?.close();
    for (final input in [package, token, version, buildNumber, title]) {
      input.dispose();
    }
    super.dispose();
  }

  Future<void> buildApk() async {
    if (!form.currentState!.validate()) return;
    setState(() {
      busy = true;
      run = null;
      apk = null;
    });
    final github = GitHubBuild(token.text.trim());
    client = github;
    void progress(String value) {
      if (mounted) setState(() => status = value);
    }

    try {
      final id = await github.submit(
        html: widget.game,
        package: package.text.trim(),
        title: title.text.trim(),
        orientation: orientation,
        version: version.text.trim(),
        buildNumber: buildNumber.text.trim(),
        icon: icon,
        progress: progress,
      );
      if (!mounted) return;
      setState(() => run = id);
      final result = await github.waitForApk(id, package.text.trim(), progress);
      if (mounted)
        setState(() {
          apk = result;
          status = 'Your APK is ready to save.';
        });
    } on GitHubBuildError catch (error) {
      progress(error.message);
    } catch (_) {
      progress(
        'Could not reach GitHub or read the download. Check your connection.',
      );
    } finally {
      github.close();
      if (identical(client, github)) client = null;
      if (mounted) setState(() => busy = false);
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

  Future<void> openGitHub(String path) async {
    try {
      await native.invokeMethod<void>('open', {
        'url': 'https://github.com/$path',
      });
    } on PlatformException {
      if (mounted) setState(() => status = 'Could not open GitHub.');
    }
  }

  Widget input(
    TextEditingController controller,
    String label, {
    required String? Function(String) validate,
    bool secret = false,
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
      obscureText: secret,
      autocorrect: false,
      enableSuggestions: !secret,
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
              'GitHub Actions builds a standalone, full-screen game. This repository is public. Uploaded game data and the build can be accessible to others.',
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
                      v == 'com.nexusengine.app'
                  ? 'Enter a unique ID such as com.example.mygame.'
                  : null,
            ),
            DropdownButtonFormField<String>(
              initialValue: orientation,
              decoration: const InputDecoration(
                labelText: 'Orientation',
                border: OutlineInputBorder(),
              ),
              items: const [
                DropdownMenuItem(value: 'auto', child: Text('Follow device')),
                DropdownMenuItem(value: 'portrait', child: Text('Portrait')),
                DropdownMenuItem(value: 'landscape', child: Text('Landscape')),
              ],
              onChanged: busy || apk != null
                  ? null
                  : (v) => setState(() => orientation = v!),
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
            input(
              token,
              'Fine-grained GitHub token',
              secret: true,
              validate: (v) => v.startsWith('github_pat_')
                  ? null
                  : 'Enter a fine-grained token.',
            ),
            const Text(
              'Select Game-Engine and grant Contents and Actions read/write. The token stays in this dialog session.',
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: busy
                    ? null
                    : () => openGitHub('settings/personal-access-tokens/new'),
                child: const Text('Create a GitHub token'),
              ),
            ),
            if (busy) const LinearProgressIndicator(),
            if (status.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(status),
              ),
            if (run != null)
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: () => openGitHub('$repository/actions/runs/$run'),
                  child: const Text('Open GitHub build'),
                ),
              ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: Text(busy && run != null ? 'Close (build continues)' : 'Close'),
      ),
      FilledButton(
        onPressed: busy
            ? null
            : apk == null
            ? buildApk
            : saveApk,
        child: Text(apk == null ? 'Build APK on GitHub' : 'Save APK'),
      ),
    ],
  );
}
