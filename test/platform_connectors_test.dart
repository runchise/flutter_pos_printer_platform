import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_pos_printer_platform_image_3/flutter_pos_printer_platform_image_3.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  late Future<Object?> Function(MethodCall call) plugin;

  setUp(() {
    calls.clear();
    messenger.setMockMethodCallHandler(flutterPrinterChannel, (call) {
      calls.add(call);
      return plugin(call);
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(flutterPrinterChannel, null);
    debugDefaultTargetPlatformOverride = null;
  });

  Map<Object?, Object?> argumentsOf(String method) =>
      calls.lastWhere((call) => call.method == method).arguments as Map<Object?, Object?>;

  group('Android Bluetooth', () {
    var connectAnswer = true;

    setUp(() {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      connectAnswer = true;
      plugin = (call) async => switch (call.method) {
        'onStartConnection' => connectAnswer,
        'sendDataByte' => {'outcome': 'delivered'},
        _ => null,
      };
    });

    test('a send names the printer this connector connected last, so the plugin can refuse the printer another engine connected', () async {
      final connector = BluetoothPrinterConnector(address: '', isBle: false);
      await connector.connect(BluetoothPrinterInput(address: '00:11:22:33:44:55'));
      await connector.connect(BluetoothPrinterInput(address: 'AA:BB:CC:DD:EE:FF', isBle: true));

      final result = await connector.send([0x1B, 0x40]);

      expect(result, isA<PrintConfirmed>());
      expect(argumentsOf('sendDataByte'), allOf(containsPair('address', 'AA:BB:CC:DD:EE:FF'), containsPair('isBle', true)));
    });

    test('a send still names the printer when its connect failed, so it never falls through to another printer', () async {
      connectAnswer = false;
      final connector = BluetoothPrinterConnector(address: '', isBle: false);

      expect(await connector.connect(BluetoothPrinterInput(address: '00:11:22:33:44:55')), isFalse);
      await connector.send([0x1B, 0x40]);

      expect(argumentsOf('sendDataByte'), allOf(containsPair('address', '00:11:22:33:44:55'), containsPair('isBle', false)));
    });

    test('a send before any connect names no printer, as an older caller did', () async {
      await BluetoothPrinterConnector(address: '', isBle: false).send([0x1B, 0x40]);

      expect(argumentsOf('sendDataByte').keys, isNot(anyOf(contains('address'), contains('isBle'))));
    });

    test('connect gives the plugin its deadline, and Dart keeps waiting a little past it for the plugin to answer', () async {
      plugin = (call) async {
        await Future<void>.delayed(const Duration(milliseconds: 300));
        return true;
      };
      final connector = BluetoothPrinterConnector(address: '', isBle: false)..connectTimeout = const Duration(milliseconds: 100);

      expect(await connector.connect(BluetoothPrinterInput(address: '00:11:22:33:44:55')), isTrue);
      expect(argumentsOf('onStartConnection'), containsPair('connectTimeoutMs', 100));
    });
  });

  group('Android USB', () {
    setUp(() {
      debugDefaultTargetPlatformOverride = TargetPlatform.android;
      plugin = (call) async => switch (call.method) {
        'connectPrinter' => true,
        'printBytes' => {'outcome': 'delivered'},
        _ => null,
      };
    });

    test('a send names the vendor and product ids this connector connected last', () async {
      final connector = UsbPrinterConnector.Android(vendorId: '', productId: '');
      await connector.connect(UsbPrinterInput(vendorId: '1155', productId: '22304'));
      await connector.connect(UsbPrinterInput(vendorId: '1659', productId: '8965'));

      final result = await connector.send([0x1B, 0x40]);

      expect(result, isA<PrintConfirmed>());
      expect(argumentsOf('printBytes'), allOf(containsPair('vendor', 1659), containsPair('product', 8965)));
    });

    test('connect gives the plugin its permission deadline', () async {
      await UsbPrinterConnector.Android(vendorId: '', productId: '').connect(UsbPrinterInput(vendorId: '1155', productId: '22304'));

      expect(argumentsOf('connectPrinter'), containsPair('permissionTimeoutMs', 60000));
    });

    test('a send after a connect whose ids are not numbers fails without reaching the plugin', () async {
      final connector = UsbPrinterConnector.Android(vendorId: '', productId: '');

      expect(await connector.connect(UsbPrinterInput(vendorId: 'abc', productId: '22304')), isFalse);
      final result = await connector.send([0x1B, 0x40]);

      expect(result, isA<PrintFailed>());
      expect(calls.where((call) => call.method == 'printBytes'), isEmpty);
    });

    test('a send before any connect names no printer, as an older caller did', () async {
      await UsbPrinterConnector.Android(vendorId: '', productId: '').send([0x1B, 0x40]);

      expect(argumentsOf('printBytes').keys, isNot(anyOf(contains('vendor'), contains('product'))));
    });
  });

  group('Windows USB', () {
    setUp(() => debugDefaultTargetPlatformOverride = TargetPlatform.windows);

    Future<PrintResult> sendWithSpoolerReply(Object? reply) {
      plugin = (call) async => call.method == 'printBytes' ? reply : null;
      return UsbPrinterConnector.Windows(name: 'POS-80').send([0x1B, 0x40]);
    }

    test('a job the spooler took whole is unconfirmed, because the printer confirms nothing through the spooler', () async {
      final result = await sendWithSpoolerReply({'outcome': 'sent', 'reason': 'Windows print spooler took every byte'});

      expect(result, isA<PrintUnconfirmed>().having((r) => r.reason, 'reason', 'Windows print spooler took every byte'));
      expect(result.sentWholeJob, isTrue);
    });

    test('a job the spooler took in part is cut off', () async {
      expect(await sendWithSpoolerReply({'outcome': 'cutOff', 'reason': 'took 10 of 20 bytes'}), isA<PrintCutOff>());
    });

    test('a job the spooler never started fails', () async {
      expect(await sendWithSpoolerReply({'outcome': 'failed', 'reason': 'did not start the job'}), isA<PrintFailed>());
    });

    test('a reply from an older plugin, which answered 1 or 0, counts as cut off', () async {
      expect(await sendWithSpoolerReply(1), isA<PrintCutOff>());
      expect(await sendWithSpoolerReply(0), isA<PrintCutOff>());
    });

    test('a send that throws closes the printer and counts as cut off', () async {
      plugin = (call) async => call.method == 'printBytes' ? throw PlatformException(code: 'spooler') : null;

      final result = await UsbPrinterConnector.Windows(name: 'POS-80').send([0x1B, 0x40]);

      expect(result, isA<PrintCutOff>());
      expect(calls.map((call) => call.method), ['printBytes', 'close']);
    });
  });
}
