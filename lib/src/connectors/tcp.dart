import 'dart:async';
import 'dart:developer' as developer;
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_pos_printer_platform_image_3/discovery.dart';
import 'package:flutter_pos_printer_platform_image_3/printer.dart';
import 'package:flutter_pos_printer_platform_image_3/src/models/printer_device.dart';
import 'package:flutter_pos_printer_platform_image_3/src/print_result.dart';
import 'package:network_info_plus/network_info_plus.dart';
import 'package:ping_discover_network_forked/ping_discover_network_forked.dart';

class TcpPrinterInput extends BasePrinterInput {
  final String ipAddress;
  final int port;
  final Duration timeout;
  TcpPrinterInput({
    required this.ipAddress,
    this.port = 9100,
    this.timeout = const Duration(seconds: 5),
  });
}

class TcpPrinterInfo {
  String address;
  TcpPrinterInfo({
    required this.address,
  });
}

class TcpPrintException implements Exception {
  final String message;

  const TcpPrintException(this.message);

  @override
  String toString() => message;
}

class _PrinterConnection {
  _PrinterConnection(this.socket) {
    _answer.future.ignore();
  }

  final Socket socket;
  final Completer<int?> _answer = Completer<int?>();
  bool _listeningForStatus = false;

  bool get hasEnded => _answer.isCompleted;

  Future<int?> get answer => _answer.future;

  void listenForStatus() => _listeningForStatus = true;

  void _onBytes(Uint8List bytes) {
    if (!_listeningForStatus || _answer.isCompleted) return;
    for (final byte in bytes) {
      if (isOfflineCauseStatus(byte)) {
        _answer.complete(byte);
        return;
      }
    }
  }

  void _markHungUp() {
    if (!_answer.isCompleted) _answer.complete(null);
  }

  void _markLost(Object error) {
    if (!_answer.isCompleted) _answer.completeError(error);
  }
}

// See README.md, "Network printing".
class TcpPrinterConnector implements PrinterConnector<TcpPrinterInput> {
  final int connectAttempts;
  final Duration retryPause;
  final Duration flushTimeout;
  final Duration confirmTimeout;
  final Duration closeTimeout;

  TcpPrinterConnector({
    this.connectAttempts = 3,
    this.retryPause = const Duration(milliseconds: 500),
    this.flushTimeout = const Duration(seconds: 60),
    this.confirmTimeout = const Duration(seconds: 60),
    this.closeTimeout = const Duration(seconds: 1),
  });

  static final TcpPrinterConnector instance = TcpPrinterConnector();

  TcpPrinterInput? _target;

  static Future<List<PrinterDiscovered<TcpPrinterInfo>>> discoverPrinters({String? ipAddress, int? port, Duration? timeOut}) async {
    final List<PrinterDiscovered<TcpPrinterInfo>> result = [];
    final defaultPort = port ?? 9100;

    String? deviceIp;
    if (Platform.isAndroid || Platform.isIOS) {
      deviceIp = await NetworkInfo().getWifiIP();
    } else if (ipAddress != null) deviceIp = ipAddress;
    if (deviceIp == null) return result;

    final String subnet = deviceIp.substring(0, deviceIp.lastIndexOf('.'));

    final stream = NetworkAnalyzer.discover2(
      subnet,
      defaultPort,
      timeout: timeOut ?? Duration(milliseconds: 4000),
    );

    await for (var addr in stream) {
      if (addr.exists) {
        result.add(PrinterDiscovered<TcpPrinterInfo>(name: "${addr.ip}:$defaultPort", detail: TcpPrinterInfo(address: addr.ip)));
      }
    }

    return result;
  }

  Stream<PrinterDevice> discovery({TcpPrinterInput? model}) async* {
    final defaultPort = model?.port ?? 9100;

    String? deviceIp;
    if (Platform.isAndroid || Platform.isIOS) {
      deviceIp = await NetworkInfo().getWifiIP();
    } else if (model?.ipAddress != null) {
      deviceIp = model!.ipAddress;
    } else {
      return;
    }

    final String subnet = deviceIp!.substring(0, deviceIp.lastIndexOf('.'));

    final stream = NetworkAnalyzer.discover2(subnet, defaultPort);

    await for (var data in stream) {
      if (data.exists) {
        yield PrinterDevice(name: "${data.ip}:$defaultPort", address: data.ip);
      }
    }
  }

  Future<PrintResult> print(TcpPrinterInput printer, List<int> bytes) async {
    final Socket socket;
    try {
      socket = await _connect(printer);
    } on TcpPrintException catch (error) {
      return PrintFailed(error.message);
    }
    return _session(socket, (connection) => _deliver(connection, '${printer.ipAddress}:${printer.port}', bytes));
  }

  Future<void> probe(TcpPrinterInput printer) async {
    final socket = await _connect(printer);
    await _session(socket, (connection) async {
      try {
        await connection.socket.close().timeout(closeTimeout);
      } on Object catch (error) {
        developer.log('Printer socket did not close cleanly after the probe, destroying it: $error');
      }
    });
  }

  @override
  Future<bool> connect(TcpPrinterInput model) async {
    _target = model;
    try {
      await probe(model);
      return true;
    } on TcpPrintException catch (error) {
      developer.log(error.message);
      return false;
    }
  }

  @override
  Future<PrintResult> send(List<int> bytes) async {
    final target = _target;
    if (target == null) return const PrintFailed('No network printer chosen. Call connect first.');
    return print(target, bytes);
  }

  @override
  Future<bool> disconnect({int? delayMs}) async {
    if (delayMs != null) {
      await Future<void>.delayed(Duration(milliseconds: delayMs));
    }
    return true;
  }

  Future<T> _session<T>(Socket socket, Future<T> Function(_PrinterConnection connection) use) async {
    socket.done.ignore();
    final connection = _PrinterConnection(socket);
    final incoming = socket.listen(connection._onBytes, onDone: connection._markHungUp, onError: connection._markLost);
    try {
      return await use(connection);
    } finally {
      await incoming.cancel();
      socket.destroy();
    }
  }

  Future<PrintResult> _deliver(_PrinterConnection connection, String printer, List<int> bytes) async {
    final socket = connection.socket;
    try {
      socket.add(bytes);
      await socket.flush().timeout(flushTimeout);
    } on TimeoutException {
      return PrintCutOff('Printer $printer did not take the whole job within ${flushTimeout.inSeconds} s');
    } on SocketException catch (error) {
      return PrintCutOff('Lost the connection to printer $printer while sending: ${_reason(error)}');
    }
    if (connection.hasEnded) {
      return PrintCutOff('Printer $printer hung up before it had the whole job');
    }
    return _awaitConfirmation(connection, printer);
  }

  Future<PrintResult> _awaitConfirmation(_PrinterConnection connection, String printer) async {
    final socket = connection.socket;
    connection.listenForStatus();
    try {
      socket.add(offlineCauseStatusRequest);
      await socket.close().timeout(closeTimeout);
    } on Object catch (error) {
      developer.log('Could not ask printer $printer for its status after the job: $error');
    }
    try {
      final status = await connection.answer.timeout(confirmTimeout);
      return status == null ? const PrintConfirmed() : PrintConfirmed(problemsInOfflineCauseStatus(status));
    } on TimeoutException {
      return PrintUnconfirmed('Printer $printer took every byte but did not confirm the job within ${confirmTimeout.inSeconds} s');
    } on Object catch (error) {
      final reason = error is SocketException ? _reason(error) : error.toString();
      return PrintUnconfirmed('Printer $printer took every byte, then the connection dropped: $reason');
    }
  }

  Future<Socket> _connect(TcpPrinterInput printer) async {
    SocketException? lastError;
    for (var attempt = 1; attempt <= connectAttempts; attempt++) {
      try {
        return await Socket.connect(printer.ipAddress, printer.port, timeout: printer.timeout);
      } on SocketException catch (error) {
        lastError = error;
        developer.log('Could not connect to printer ${printer.ipAddress}:${printer.port}, attempt $attempt of $connectAttempts: ${_reason(error)}');
        if (attempt < connectAttempts) {
          await Future<void>.delayed(retryPause);
        }
      }
    }
    throw TcpPrintException(
      'Could not connect to printer ${printer.ipAddress}:${printer.port}'
      '${lastError == null ? '' : ': ${_reason(lastError)}'}',
    );
  }

  static String _reason(SocketException error) => error.osError?.message.trim().isNotEmpty == true
      ? error.osError!.message.trim()
      : error.message;
}
