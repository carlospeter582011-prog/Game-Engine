import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';

const repository = 'carlospeter582011-prog/Game-Engine';
const defaultBranch = 'main';
const maxGameBytes = 20 * 1024 * 1024;
final packagePattern = RegExp(r'^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$');

class GitHubBuild {
  GitHubBuild(this.token) {
    _client.connectionTimeout = const Duration(seconds: 20);
  }

  final String token;
  final _client = HttpClient();
  static const _repo = '/repos/$repository';

  void close() => _client.close(force: true);

  Future<({HttpClientResponse response, Uri uri})> _request(
    String method,
    String path, {
    Object? body,
    String accept = 'application/vnd.github+json',
  }) async {
    final uri = Uri.https('api.github.com', path);
    final request = await _client.openUrl(method, uri);
    request.followRedirects = false;
    request.headers
      ..set('Authorization', 'Bearer $token')
      ..set('Accept', accept)
      ..set('X-GitHub-Api-Version', '2026-03-10')
      ..set('User-Agent', 'Nexus-Engine-Android');
    if (body != null) {
      request.headers.contentType = ContentType.json;
      request.add(utf8.encode(jsonEncode(body)));
    }
    final response = await request.close().timeout(const Duration(seconds: 60));
    return (response: response, uri: uri);
  }

  Future<Map<String, dynamic>> _json(
    String method,
    String path, {
    Object? body,
  }) async {
    final result = await _request(method, path, body: body);
    final content = await utf8.decoder.bind(result.response).join();
    if (result.response.statusCode < 200 || result.response.statusCode >= 300) {
      throw GitHubBuildError(
        'GitHub returned HTTP ${result.response.statusCode}. '
        'Check repository access and the token’s Contents and Actions permissions.',
      );
    }
    return content.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(content) as Map<String, dynamic>;
  }

  Future<int> submit({
    required String html,
    required String package,
    required String title,
    required String orientation,
    required String version,
    required String buildNumber,
    required Uint8List? icon,
    required void Function(String) progress,
  }) async {
    final bytes = utf8.encode(html);
    if (bytes.length > maxGameBytes) {
      throw const GitHubBuildError('The standalone game exceeds 20 MB.');
    }
    progress('Uploading game data…');
    final blob = await _json(
      'POST',
      '$_repo/git/blobs',
      body: {'encoding': 'base64', 'content': base64Encode(bytes)},
    );
    final iconBlob = icon == null
        ? null
        : await _json(
            'POST',
            '$_repo/git/blobs',
            body: {'encoding': 'base64', 'content': base64Encode(icon)},
          );
    progress('Starting GitHub Actions…');
    final response = await _json(
      'POST',
      '$_repo/actions/workflows/export-game.yml/dispatches',
      body: {
        'ref': defaultBranch,
        'inputs': {
          'game_blob': blob['sha'] as String,
          'icon_blob': iconBlob?['sha'] as String? ?? '',
          'package_id': package,
          'app_name': title,
          'orientation': orientation,
          'version_name': version,
          'build_number': buildNumber,
        },
      },
    );
    final run = response['workflow_run_id'];
    if (run is! int) {
      throw const GitHubBuildError(
        'GitHub did not return a run ID. Check the Actions tab.',
      );
    }
    return run;
  }

  Future<Uint8List> waitForApk(
    int run,
    String package,
    void Function(String) progress,
  ) async {
    final until = DateTime.now().add(const Duration(minutes: 25));
    while (DateTime.now().isBefore(until)) {
      final state = await _json('GET', '$_repo/actions/runs/$run');
      if (state['status'] == 'completed') {
        if (state['conclusion'] != 'success') {
          throw GitHubBuildError(
            'Build ended with ${state['conclusion']}. Open the run for logs.',
          );
        }
        progress('Downloading APK…');
        final artifacts = await _json(
          'GET',
          '$_repo/actions/runs/$run/artifacts',
        );
        final artifact = (artifacts['artifacts'] as List)
            .cast<Map<String, dynamic>>()
            .where(
              (entry) => entry['name'] == package && entry['expired'] == false,
            )
            .firstOrNull;
        if (artifact == null)
          throw const GitHubBuildError('APK artifact is missing or expired.');
        return _download(artifact['id'] as int, package);
      }
      progress(
        state['status'] == 'in_progress'
            ? 'Building the APK…'
            : 'Waiting for a GitHub runner…',
      );
      await Future<void>.delayed(const Duration(seconds: 8));
    }
    throw const GitHubBuildError(
      'The build is taking longer than expected. Open its GitHub run to continue.',
    );
  }

  Future<Uint8List> _download(int artifact, String package) async {
    final result = await _request(
      'GET',
      '$_repo/actions/artifacts/$artifact/zip',
    );
    if (result.response.statusCode != 302) {
      await result.response.drain<void>();
      throw const GitHubBuildError(
        'GitHub could not provide the APK download.',
      );
    }
    final target = result.uri.resolve(
      result.response.headers.value('location') ?? '',
    );
    await result.response.drain<void>();
    if (target.scheme != 'https' || target.host == result.uri.host) {
      throw const GitHubBuildError('GitHub returned an invalid download URL.');
    }
    // The artifact URL has its own credentials. Do not forward the GitHub token.
    final download = await _client.getUrl(target);
    final response = await download.close().timeout(
      const Duration(seconds: 60),
    );
    if (response.statusCode != 200) {
      await response.drain<void>();
      throw const GitHubBuildError(
        'The APK download link could not be opened.',
      );
    }
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in response.timeout(const Duration(seconds: 45))) {
      bytes.add(chunk);
      if (bytes.length > 160 * 1024 * 1024) {
        throw const GitHubBuildError('APK download exceeds the size limit.');
      }
    }
    final zip = ZipDecoder().decodeBytes(bytes.takeBytes(), verify: true);
    final file = zip.findFile('$package.apk');
    if (file == null || !file.isFile)
      throw const GitHubBuildError(
        'Download did not contain the expected APK.',
      );
    return file.content;
  }
}

class GitHubBuildError implements Exception {
  const GitHubBuildError(this.message);
  final String message;
  @override
  String toString() => message;
}
