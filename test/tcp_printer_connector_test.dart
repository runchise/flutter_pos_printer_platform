import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_pos_printer_platform_image_3/flutter_pos_printer_platform_image_3.dart';
import 'package:flutter_test/flutter_test.dart';

class _Connection {
  final BytesBuilder received = BytesBuilder();
  final Completer<void> closedByApp = Completer<void>();
  late final StreamSubscription<Uint8List> subscription;

  Future<void> stopListening() => subscription.cancel();
}

class _FakePrinter {
  final ServerSocket _server;
  final List<_Connection> connections = [];

  _FakePrinter._(this._server);

  int get port => _server.port;

  TcpPrinterInput get input => TcpPrinterInput(ipAddress: '127.0.0.1', port: port);

  static Future<_FakePrinter> start({
    List<int> answerOnConnect = const [],
    int? statusReply,
    bool stopReading = false,
    Duration busyFor = Duration.zero,
    bool hangsUpWhenDone = true,
    bool hangsUpOnConnect = false,
  }) async {
    final printer = _FakePrinter._(await ServerSocket.bind(InternetAddress.loopbackIPv4, 0));
    printer._server.listen((socket) {
      final connection = _Connection();
      printer.connections.add(connection);
      if (answerOnConnect.isNotEmpty) {
        socket.add(answerOnConnect);
      }
      if (hangsUpOnConnect) {
        socket.close().ignore();
      }
      var answered = false;
      connection.subscription = socket.listen(
        (bytes) {
          connection.received.add(bytes);
          final all = connection.received.toBytes();
          if (statusReply != null && !answered && _endsWithStatusRequest(all)) {
            answered = true;
            socket.add([statusReply]);
          }
        },
        onDone: () {
          if (hangsUpWhenDone) {
            socket.destroy();
          }
          connection.closedByApp.complete();
        },
        onError: (Object _) => socket.destroy(),
      );
      if (stopReading) {
        connection.subscription.pause();
      } else if (busyFor > Duration.zero) {
        connection.subscription.pause(Future<void>.delayed(busyFor));
      }
    });
    return printer;
  }

  static bool _endsWithStatusRequest(Uint8List bytes) =>
      bytes.length >= 3 && bytes[bytes.length - 3] == 0x10 && bytes[bytes.length - 2] == 0x04 && bytes[bytes.length - 1] == 0x02;

  Future<void> stop() async {
    await Future.wait(connections.map((connection) => connection.stopListening()));
    await _server.close();
  }
}

Future<int> _closedPort() async {
  final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
  final port = server.port;
  await server.close();
  return port;
}

List<int> _withStatusRequest(List<int> job) => [...job, ...offlineCauseStatusRequest];

void main() {
  late _FakePrinter printer;

  tearDown(() => printer.stop());

  test('a printer that hangs up after the job confirms it, and gets every byte plus one status request', () async {
    printer = await _FakePrinter.start();
    final job = List<int>.generate(200000, (i) => i % 251);

    final result = await TcpPrinterConnector().print(printer.input, job);

    expect(result, isA<PrintConfirmed>().having((r) => r.problems, 'problems', isEmpty));
    final connection = printer.connections.single;
    await connection.closedByApp.future.timeout(const Duration(seconds: 5));
    expect(connection.received.takeBytes(), _withStatusRequest(job));
  });

  test('a printer that answers the status request confirms the job without hanging up', () async {
    printer = await _FakePrinter.start(statusReply: 0x12, hangsUpWhenDone: false);

    final result = await TcpPrinterConnector(confirmTimeout: const Duration(seconds: 5)).print(printer.input, [0x1B, 0x40]);

    expect(result, isA<PrintConfirmed>().having((r) => r.problems, 'problems', isEmpty));
  });

  test('a status reply that says cover open and paper out confirms the job and names both problems', () async {
    printer = await _FakePrinter.start(statusReply: 0x12 | 0x04 | 0x20, hangsUpWhenDone: false);

    final result = await TcpPrinterConnector().print(printer.input, [0x1B, 0x40]);

    expect(
      result,
      isA<PrintConfirmed>().having((r) => r.problems, 'problems', {PrinterProblem.coverOpen, PrinterProblem.paperOut}),
    );
  });

  test('a busy printer is confirmed only after it has read the whole job', () async {
    printer = await _FakePrinter.start(busyFor: const Duration(seconds: 2));
    final job = List<int>.generate(64 * 1024, (i) => i % 251);

    final result = await TcpPrinterConnector().print(printer.input, job);

    expect(result, isA<PrintConfirmed>());
    final connection = printer.connections.single;
    expect(connection.closedByApp.isCompleted, isTrue);
    expect(connection.received.takeBytes(), _withStatusRequest(job));
  });

  test('a printer that takes every byte but never answers or hangs up is unconfirmed, not failed', () async {
    printer = await _FakePrinter.start(hangsUpWhenDone: false);

    final result = await TcpPrinterConnector(confirmTimeout: const Duration(seconds: 1)).print(printer.input, [0x1B, 0x40]);

    expect(
      result,
      isA<PrintUnconfirmed>().having(
        (r) => r.reason,
        'reason',
        'Printer 127.0.0.1:${printer.port} took every byte but did not confirm the job within 1 s',
      ),
    );
  });

  test('a printer that hangs up while the job is still being sent cuts the job off', () async {
    printer = await _FakePrinter.start(hangsUpOnConnect: true, busyFor: const Duration(seconds: 1));

    final result = await TcpPrinterConnector().print(printer.input, Uint8List(16 * 1024 * 1024));

    expect(result, isA<PrintCutOff>());
  });

  test('bytes the printer sends back on connect do not break the job', () async {
    printer = await _FakePrinter.start(answerOnConnect: [0x10, 0x04, 0x12]);

    final result = await TcpPrinterConnector().print(printer.input, [0x1B, 0x40, 0x41]);

    expect(result, isA<PrintConfirmed>());
    final connection = printer.connections.single;
    await connection.closedByApp.future.timeout(const Duration(seconds: 5));
    expect(connection.received.takeBytes(), _withStatusRequest([0x1B, 0x40, 0x41]));
  });

  test('probe opens one connection and closes it without sending anything', () async {
    printer = await _FakePrinter.start();

    await TcpPrinterConnector().probe(printer.input);

    final connection = printer.connections.single;
    await connection.closedByApp.future.timeout(const Duration(seconds: 5));
    expect(connection.received.isEmpty, isTrue);
  });

  test('a port with no printer fails after every attempt and names the address', () async {
    printer = await _FakePrinter.start();
    final port = await _closedPort();
    final watch = Stopwatch()..start();

    final result = await TcpPrinterConnector(retryPause: const Duration(milliseconds: 10))
        .print(TcpPrinterInput(ipAddress: '127.0.0.1', port: port), [1]);

    expect(result, isA<PrintFailed>().having((r) => r.reason, 'reason', startsWith('Could not connect to printer 127.0.0.1:$port')));
    expect(watch.elapsed, lessThan(const Duration(seconds: 10)));
  });

  test('probe of a port with no printer throws and names the address', () async {
    printer = await _FakePrinter.start();
    final port = await _closedPort();

    await expectLater(
      TcpPrinterConnector(connectAttempts: 1).probe(TcpPrinterInput(ipAddress: '127.0.0.1', port: port)),
      throwsA(isA<TcpPrintException>().having((e) => e.message, 'message', startsWith('Could not connect to printer 127.0.0.1:$port'))),
    );
  });

  test('a printer that stops reading cuts the job off at the flush time limit', () async {
    printer = await _FakePrinter.start(stopReading: true);

    final result = await TcpPrinterConnector(flushTimeout: const Duration(seconds: 1))
        .print(printer.input, Uint8List(64 * 1024 * 1024));

    expect(
      result,
      isA<PrintCutOff>().having((r) => r.reason, 'reason', 'Printer 127.0.0.1:${printer.port} did not take the whole job within 1 s'),
    );
  });

  group('platform reply', () {
    test('a delivered reply with a status names the problems in it', () {
      expect(
        printResultFromPlatformReply({'outcome': 'delivered', 'status': 0x12 | 0x40}),
        isA<PrintConfirmed>().having((r) => r.problems, 'problems', {PrinterProblem.error}),
      );
    });

    test('a sent reply with a status is confirmed', () {
      expect(printResultFromPlatformReply({'outcome': 'sent', 'status': 0x12}), isA<PrintConfirmed>());
    });

    test('a sent reply with no status is unconfirmed and keeps the reason', () {
      expect(
        printResultFromPlatformReply({'outcome': 'sent', 'reason': 'no answer'}),
        isA<PrintUnconfirmed>().having((r) => r.reason, 'reason', 'no answer'),
      );
    });

    test('a failed reply is a failure and keeps the reason', () {
      expect(
        printResultFromPlatformReply({'outcome': 'failed', 'reason': 'cable out'}),
        isA<PrintFailed>().having((r) => r.reason, 'reason', 'cable out'),
      );
    });

    test('a cut-off reply is cut off and keeps the reason', () {
      expect(
        printResultFromPlatformReply({'outcome': 'cutOff', 'reason': 'stopped at byte 8192'}),
        isA<PrintCutOff>().having((r) => r.reason, 'reason', 'stopped at byte 8192'),
      );
    });

    test('a reply the fork does not know, such as an older plugin\'s bool, counts as cut off, never as failed', () {
      expect(printResultFromPlatformReply(true), isA<PrintCutOff>());
      expect(printResultFromPlatformReply({'outcome': 'later'}), isA<PrintCutOff>());
    });

    test('only confirmed and unconfirmed results sent the whole job', () {
      expect(const PrintConfirmed().sentWholeJob, isTrue);
      expect(const PrintUnconfirmed('no answer').sentWholeJob, isTrue);
      expect(const PrintCutOff('stopped').sentWholeJob, isFalse);
      expect(const PrintFailed('no link').sentWholeJob, isFalse);
    });
  });

  test('status bytes are told apart from automatic status back and XON/XOFF bytes', () {
    expect(isOfflineCauseStatus(0x12), isTrue);
    expect(isOfflineCauseStatus(0x12 | 0x04 | 0x20 | 0x40), isTrue);
    expect(isOfflineCauseStatus(0x10), isFalse);
    expect(isOfflineCauseStatus(0x11), isFalse);
    expect(isOfflineCauseStatus(0x13), isFalse);
  });
}
