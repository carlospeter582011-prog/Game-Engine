import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

Widget buildWebEditor() => HtmlElementView.fromTagName(
  tagName: 'iframe',
  onElementCreated: (element) {
    final frame = element as web.HTMLIFrameElement;
    frame
      ..title = 'Nexus Engine editor'
      ..src = 'assets/assets/web/editor.html';
    frame.style
      ..width = '100%'
      ..height = '100%'
      ..border = '0';
  },
);
