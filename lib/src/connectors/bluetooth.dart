import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_pos_printer_platform_image_3/discovery.dart';
import 'package:flutter_pos_printer_platform_image_3/flutter_pos_printer_platform_image_3.dart';
import 'package:rxdart/rxdart.dart';

class BluetoothPrinterInput extends BasePrinterInput {
  final String address;
  final String? name;
  final bool isBle;
  final bool autoConnect;
  BluetoothPrinterInput({
    required this.address,
    this.name,
    this.isBle = false,
    this.autoConnect = false,
  });
}

class BluetoothPrinterDevice {
  final String? address;

  BluetoothPrinterDevice({required this.address});
}

class BluetoothPrinterConnector implements PrinterConnector<BluetoothPrinterInput> {
  BluetoothPrinterConnector._()
      : address = "",
        isBle = false {
    if (defaultTargetPlatform == TargetPlatform.android)
      flutterPrinterChannel.setMethodCallHandler((MethodCall call) {
        _methodStreamController.add(call);
        return Future(() => null);
      });

    if (defaultTargetPlatform == TargetPlatform.iOS)
      iosChannel.setMethodCallHandler((MethodCall call) {
        _methodStreamController.add(call);
        return Future(() => null);
      });

    if (defaultTargetPlatform == TargetPlatform.android)
      flutterPrinterEventChannelBT.receiveBroadcastStream().listen((data) {
        if (data is int) {
          // log('Received event status: $data');
          _status = BTStatus.values[data];
          _statusStreamController.add(_status);
        }
      });

    if (defaultTargetPlatform == TargetPlatform.iOS) {
      //  iosChannel.invokeMethod('state').then((s) => s);
      iosStateChannel.receiveBroadcastStream().listen((data) {
        if (data is int) {
          // log('Received event status: $data');
          _status = BTStatus.values[data];
          _statusStreamController.add(_status);
        }
      });
    }
  }
  static BluetoothPrinterConnector _instance = BluetoothPrinterConnector._();

  static BluetoothPrinterConnector get instance => _instance;

  Stream<MethodCall> get _methodStream => _methodStreamController.stream;
  final StreamController<MethodCall> _methodStreamController = StreamController.broadcast();
  PublishSubject _stopScanPill = new PublishSubject();

  BehaviorSubject<bool> _isScanning = BehaviorSubject.seeded(false);
  Stream<bool> get isScanning => _isScanning.stream;

  BehaviorSubject<List<PrinterDevice>> _scanResults = BehaviorSubject.seeded([]);
  Stream<List<PrinterDevice>> get scanResults => _scanResults.stream;

  Stream<BTStatus> get _statusStream => _statusStreamController.stream;
  final StreamController<BTStatus> _statusStreamController = StreamController.broadcast();

  BluetoothPrinterConnector({required this.address, required this.isBle, this.name}) {
    flutterPrinterChannel.setMethodCallHandler((MethodCall call) {
      _methodStreamController.add(call);
      return Future(() => null);
    });
  }

  String address;
  String? name;
  bool isBle;
  BTStatus _status = BTStatus.none;
  BTStatus get status => _status;

  StreamController<String> devices = new StreamController.broadcast();

  setAddress(String address) => this.address = address;
  setName(String name) => this.name = name;
  setIsBle(bool isBle) => this.isBle = isBle;

  static DiscoverResult<BluetoothPrinterDevice> discoverPrinters({bool isBle = false}) => instance
      .discovery(isBle: isBle)
      .map((device) => PrinterDiscovered<BluetoothPrinterDevice>(
            name: device.name,
            detail: BluetoothPrinterDevice(address: device.address),
          ))
      .toList();

  /// Starts a scan for Bluetooth Low Energy devices
  /// Timeout closes the stream after a specified [Duration]
  /// this device is low energy [isBle]
  Stream<PrinterDevice> discovery({
    bool isBle = false,
    Duration? timeout = const Duration(seconds: 7),
  }) async* {
    final killStreams = <Stream>[];
    killStreams.add(_stopScanPill);
    killStreams.add(Rx.timer(null, timeout!));
    // Clear scan results list
    _scanResults.add(<PrinterDevice>[]);

    if (defaultTargetPlatform == TargetPlatform.android) {
      isBle ? flutterPrinterChannel.invokeMethod('getBluetoothLeList') : flutterPrinterChannel.invokeMethod('getBluetoothList');

      await for (dynamic data in _methodStream
          .where((m) => m.method == "ScanResult")
          .map((m) => m.arguments)
          .takeUntil(Rx.merge(killStreams))
          // .takeUntil(TimerStream(3, Duration(seconds: 5)))
          .doOnDone(stopScan)
          .map((message) => message)) {
        var device = PrinterDevice(name: data['name'] as String, address: data['address'] as String?);
        if (!_addDevice(device)) continue;
        yield device;
      }
    } else if (defaultTargetPlatform == TargetPlatform.iOS) {
      try {
        await iosChannel.invokeMethod('startScan');
      } catch (e) {
        print('Error starting scan.');
        _stopScanPill.add(null);
        _isScanning.add(false);
        throw e;
      }

      await for (dynamic data in _methodStream
          .where((m) => m.method == "ScanResult")
          .map((m) => m.arguments)
          .takeUntil(Rx.merge(killStreams))
          .doOnDone(stopScan)
          .map((message) => message)) {
        print('Scan result: $data');
        final device = PrinterDevice(name: data['name'] as String, address: data['address'] as String?);
        if (!_addDevice(device)) continue;
        yield device;
      }
    }
  }

  bool _addDevice(PrinterDevice device) {
    bool isDeviceAdded = true;
    final list = _scanResults.value;
    if (!list.any((e) => e.address == device.address))
      list.add(device);
    else
      isDeviceAdded = false;
    _scanResults.add(list);
    return isDeviceAdded;
  }

  /// Start a scan for Bluetooth Low Energy devices
  Future startScan({
    Duration? timeout,
  }) async {
    await discovery(timeout: timeout).drain();
    return _scanResults.value;
  }

  /// Stops a scan for Bluetooth Low Energy devices
  Future stopScan() async {
    if (defaultTargetPlatform == TargetPlatform.iOS) await iosChannel.invokeMethod('stopScan');
    _stopScanPill.add(null);
    _isScanning.add(false);
  }

  Future<bool> _connect(BluetoothPrinterInput model) async {
    if (defaultTargetPlatform == TargetPlatform.android) {
      Map<String, dynamic> params = {
        "address": model.address,
        "isBle": model.isBle,
        "autoConnect": model.autoConnect,
        "connectTimeoutMs": connectTimeout.inMilliseconds,
      };
      final connected = flutterPrinterChannel.invokeMethod<bool>('onStartConnection', params);
      return await connected.timeout(connectTimeout + nativeDeadlineMargin, onTimeout: () => false) ?? false;
    } else if (defaultTargetPlatform == TargetPlatform.iOS) {
      Map<String, dynamic> params = {"name": model.name ?? name, "address": model.address};
      return await iosChannel.invokeMethod<bool>('connect', params).timeout(connectTimeout, onTimeout: () => false) ?? false;
    }
    return false;
  }

  /// Gets the current state of the Bluetooth module
  Stream<BTStatus> get currentStatus async* {
    // if (Platform.isAndroid) {
    yield* _statusStream.cast<BTStatus>();

    /*} else if (Platform.isIOS) {
      await iosChannel.invokeMethod('state').then((s) => s);
      await for (dynamic data in iosStateChannel.receiveBroadcastStream().map((s) => s)) {
        if (data is int) {
          yield BTStatus.values[data];
        }
      }
      // yield* iosStateChannel.receiveBroadcastStream().map((s) => s);
    }*/
  }

  @override
  Future<bool> disconnect({int? delayMs}) async {
    if (defaultTargetPlatform == TargetPlatform.android) return await flutterPrinterChannel.invokeMethod<bool>('disconnect') ?? false;
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      await iosChannel.invokeMethod('disconnect');
      return true;
    }
    return false;
  }

  Future<dynamic> destroy() => iosChannel.invokeMethod('destroy');

  Duration connectTimeout = const Duration(seconds: 20);
  Duration writeTimeout = const Duration(seconds: 60);
  Duration confirmTimeout = const Duration(seconds: 30);

  BluetoothPrinterInput? _lastConnected;

  // See README.md, "Print results" (wrong printer).
  @override
  Future<PrintResult> send(List<int> bytes) async {
    try {
      if (defaultTargetPlatform == TargetPlatform.android) {
        final printer = _lastConnected;
        final reply = await flutterPrinterChannel.invokeMethod<Object?>('sendDataByte', {
          'bytes': bytes,
          'writeTimeoutMs': writeTimeout.inMilliseconds,
          'confirmTimeoutMs': confirmTimeout.inMilliseconds,
          if (printer != null) 'address': printer.address,
          if (printer != null) 'isBle': printer.isBle,
        });
        return printResultFromPlatformReply(reply);
      } else if (defaultTargetPlatform == TargetPlatform.iOS) {
        await iosChannel.invokeMethod<Object?>('writeData', {'bytes': bytes, 'length': bytes.length});
        return const PrintUnconfirmed('Sent over Bluetooth. iOS gives no printer confirmation.');
      } else {
        return const PrintFailed('Bluetooth printing works only on Android and iOS');
      }
    } on PlatformException catch (error) {
      return PrintCutOff('Bluetooth send stopped, so part of the job may have been sent: ${error.message ?? error.code}');
    }
  }

  @override
  Future<bool> connect(BluetoothPrinterInput model) async {
    _lastConnected = model;
    try {
      return await _connect(model);
    } catch (e) {
      return false;
    }
  }
}
