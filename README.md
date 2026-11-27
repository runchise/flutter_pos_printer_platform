# flutter_pos_printer_platform

[![Pub Version](https://img.shields.io/badge/pub-v1.1.1-green)](https://pub.dev/packages/flutter_pos_printer_platform_image_3)

A library to discover printers, and send printer commands.

This library allows to print esc commands to printers in different platforms such as android, ios, windows and different interfaces as Bluetooth and BLE, USB and Wifi/Ethernet

Inspired by [flutter_pos_printer](https://github.com/feedmepos/flutter_printer/tree/master/packages/flutter_pos_printer).


## Main Features
* Android, iOS and Windows support
* Scan for bluetooth devices
* Send raw `List<int> bytes` data to a device, review this library to generate ESC/POS commands [flutter_esc_pos_utils](https://pub.dev/packages/flutter_esc_pos_utils).

## Features

|                         |      Android       |         iOS          |      Windows       |            Description            |
| :---------------        | :----------------: | :------------------: | :----------------: | :-------------------------------- |
| USB interface           | :white_check_mark: |  :white_square_button: | :white_check_mark: | Allows connection with usb devices. |
| Bluetooth classic interface | :white_check_mark: |  :white_square_button:  | :white_square_button: | Allows connection with classic bt devices. |
| Bluetooth low energy (BLE) interface | :white_check_mark: |  :white_check_mark:  | :white_square_button: | Allows connection with bt BLE devices. |
| Net (ethernet/wifi) interface | :white_check_mark: |  :white_check_mark:  | :white_check_mark: | Allows connection with network devices. |
| scan                    | :white_check_mark: |  :white_check_mark:  | :white_check_mark: | Starts a scan for only Bluetooth devices or network devices(Android/iOS). |
| connect                 | :white_check_mark: |  :white_check_mark:  | :white_check_mark: | Establishes a connection to the device. |
| disconnect              | :white_check_mark: |  :white_check_mark:  | :white_check_mark: | Cancels an active or pending connection to the device. |
| state                   | :white_check_mark: |  :white_check_mark:  | :white_check_mark: | Stream of state changes for the Bluetooth Device. |
| print                   | :white_check_mark: |  :white_check_mark:  | :white_check_mark: | print bytes. |

## Getting Started

For a full example please check /example folder. Here are only the most important parts of the code to illustrate how to use the library.

Generate bytes to print through [flutter_esc_pos_utils](https://pub.dev/packages/flutter_esc_pos_utils).

```dart
    import 'package:esc_pos_utils/esc_pos_utils.dart';

    final profile = await CapabilityProfile.load();
    final generator = Generator(PaperSize.mm58, profile);
    List<int> bytes = [];

    bytes += generator.text('Test Print', styles: const PosStyles(align: PosAlign.center));
    bytes += generator.text('Product 1');
    bytes += generator.text('Product 2');
```

## Android
Allow to connect bluetooth (classic and BLE), USB and network devices

### Change the minSdkVersion for Android

flutter_pos_printer_platform is compatible only from version 21 of Android SDK so you should change this in android/app/build.gradle:

In build.gradle set
```
    defaultConfig {
        ...
        minSdkVersion 21
        targetSdkVersion 31
        ...
```

select type of device `PrinterType` ( bluetooth, usb, network)

if select bluetooth you can send optional params

- isBle -> allow to connect with bluetooth that supports this technology
- autoconnect -> allow to reconnect when state of device is None

On Android, USB `connect` returns only after Android says whether the app may use the printer. The first time, Android shows a permission dialog and `connect` waits for the tap, at most `UsbPrinterConnector.instance.permissionTimeout` (60 s). A refused or unanswered dialog returns false.

## iOS
Allow to connect bluetooth (BLE) and network devices

## Windows
Allow to connect USB and network devices
To network devices is necessary to set ipAddress


## How to use it
### init a PrinterManager instance

```dart
import 'package:flutter_pos_printer_platform/flutter_pos_printer_platform.dart';

    var printerManager = PrinterManager.instance;

 ```

### scan

```dart
    var devices = [];
    _scan(PrinterType type, {bool isBle = false}) {
        // Find printers
        PrinterManager.instance.discovery(type: type, isBle: isBle).listen((device) {
            devices.add(device);
        });
    }
```

### connect

```dart
_connectDevice(PrinterDevice selectedPrinter, PrinterType type, {bool reconnect = false, bool isBle = false, String? ipAddress = null}) async {
    switch (type) {
      // only windows and android
      case PrinterType.usb:
        await PrinterManager.instance.connect(
            type: type,
            model: UsbPrinterInput(name: selectedPrinter.name, productId: selectedPrinter.productId, vendorId: selectedPrinter.vendorId));
        break;
      // only iOS and android
      case PrinterType.bluetooth:
        await PrinterManager.instance.connect(
            type: type,
            model: BluetoothPrinterInput(
                name: selectedPrinter.name,
                address: selectedPrinter.address!,
                isBle: isBle,
                autoConnect: reconnect));
        break;
      case PrinterType.network:
        await PrinterManager.instance.connect(type: type, model: TcpPrinterInput(ipAddress: ipAddress ?? selectedPrinter.address!));
        break;
      default:
    }
  }
```
### disconnect

```dart
    _disconnectDevice(PrinterType type) async {
        await PrinterManager.instance.disconnect(type: type);
        }
```

### listen bluetooth state
```dart
    PrinterManager.instance.stateBluetooth.listen((status) {
      log(' ----------------- status bt $status ------------------ ');
    });
```

### send bytes to print
```dart
final result = await PrinterManager.instance.send(type: type, bytes: bytes);
switch (result) {
  case PrintConfirmed(:final problems):
    // The printer has the job. `problems` names a cover open, no paper or an error.
  case PrintUnconfirmed(:final reason):
    // Every byte left the device, but the printer did not answer.
  case PrintCutOff(:final reason):
    // Part of the job may have printed. Sending it again can print that part twice.
  case PrintFailed(:final reason):
    // No byte left the device. Sending it again prints it once.
}
```

## Print results

Every `send` returns a `PrintResult`. It reports what the printer proved. It never decides a retry: the app does that.

- `PrintConfirmed(problems)`: the printer has every byte of the job. `problems` holds what the printer reported in its status reply: `coverOpen`, `paperOut` or `error`. A printer with the cover open or no paper keeps the job and prints it when that is fixed. `error` can also be an error that needs a power cycle, and a power cycle empties the printer's buffer.
- `PrintUnconfirmed(reason)`: every byte left the device, but the printer gave no proof that it read them. Sending the job again can print it twice.
- `PrintCutOff(reason)`: part of the job may have reached the printer, but not all of it. The printer can print the part it has and lose the rest.
- `PrintFailed(reason)`: no byte of the job left the device.

When the fork cannot tell whether a byte left, it reports `PrintCutOff`, never `PrintFailed`. An outcome it does not know, such as a reply from an older plugin, is also `PrintCutOff`.

`PrintResult.sentWholeJob` is true for `PrintConfirmed` and `PrintUnconfirmed`.

The proof is the ESC/POS status request `DLE EOT 2` (bytes `10 04 02`), sent after the job. A printer reads its input in order, so its reply proves it read the whole job. A reply byte has the shape `byte & 0x93 == 0x12`. Bit 2 means the cover is open, bit 5 means the paper ran out, and bit 6 means an error. Automatic status back bytes and XON/XOFF bytes never have that shape.

| Transport | `PrintConfirmed` | `PrintUnconfirmed` | `PrintCutOff` | `PrintFailed` |
|---|---|---|---|---|
| Network | Status reply, or the printer hangs up after it read the job | No answer within `confirmTimeout` (60 s), or the link drops after the whole job left | Flush timeout (`flushTimeout`, 60 s), or the link drops while sending | Cannot connect |
| Android USB | Every `bulkTransfer` took its whole piece. A status reply on the IN endpoint, within `statusTimeout` (2 s), adds problems | Never | Any `bulkTransfer` that was submitted took less than its piece or failed, the first one included | Connected to a different printer, cannot open the device, or the device has no OUT endpoint: no transfer was submitted |
| Android Bluetooth Classic | Status reply within `confirmTimeout` (30 s) | The whole job and the status request were written, but no status reply came within `confirmTimeout`. The link is then closed | A write throws, the job is not written within `writeTimeout` (60 s), the status request cannot be written, or the link drops while the app waits for the status reply | Not connected, or connected to a different printer |
| Android BLE | Each piece was written with a response | The characteristic takes only writes with no response | A piece got no answer within 5 s (the BLE connection is then closed), the link dropped, or a piece after the first one was refused | Not connected, connected to a different printer, no writable characteristic, or the first piece was refused |
| iOS Bluetooth | Never | Always: the iOS SDK gives no answer | The platform call throws | Never |
| Windows USB | Never | The print spooler took every byte. The spooler gives no printer confirmation | The spooler took part of the job, or took every byte but could not end the job. The job is cancelled and the printer closed. The platform call throws | No printer is open, or the spooler did not start the job or the page, or took no byte |

Any Android send that throws inside the plugin is `PrintCutOff`, because the plugin cannot tell how far it got.

A status reply proves only that the printer read every byte before the status request. A raster image can contain the bytes `10 04 01` to `10 04 04` by chance. Some printers answer those at once, as real-time commands, before they read the rest of the job. That can turn `PrintUnconfirmed` into `PrintConfirmed`, but never into `PrintFailed` or `PrintCutOff`.

On Bluetooth Classic, "written" means the Android Bluetooth stack took the bytes, not that they went over the air. A status request that got no answer within `confirmTimeout` closes the link, so its late reply can never confirm the next job. The next `connect` opens a new link.

On BLE, a piece that got no answer within 5 s closes the BLE connection, so a late write answer can never count for a later piece or a later job. Each write answer belongs to one connection, and an answer from a closed connection is ignored. The next `connect` opens a new connection.

### Wrong printer

An app can run two Flutter engines in one Android process. Both engines share the plugin's one Bluetooth link and one USB device, but each engine has its own Dart connector. Each connector remembers the printer it connected last, even when that connect failed, and names it with every send:

- Bluetooth `sendDataByte` gets `address` and `isBle`.
- Android USB `printBytes` gets `vendor` and `product`.

If the plugin is connected to another printer, the send is `PrintFailed("Connected to a different printer ...")` and no byte leaves. A Bluetooth send to a printer that is not connected is `PrintFailed`. A USB connector whose last `connect` had a vendor id or product id that is not a number returns `PrintFailed` without calling the plugin. A send before any `connect` names no printer and goes to whatever printer the plugin has connected.

### Connect and the worker threads

Android sends run on one worker thread per transport, so a slow printer never blocks the UI thread. Bluetooth `connect`, `disconnect` and the auto-connect retry run on the Bluetooth worker, and USB `connectPrinter`, `close`, the permission answer and the unplug event run on the USB worker. A connect from either engine therefore waits until the send in progress ends, and never closes a link in the middle of a write.

Bluetooth `connect` to a different printer, or to the other Bluetooth kind, stops the old link first. Bluetooth `connect` answers `true` only when the link is usable: on Bluetooth Classic when the socket is open, and on BLE when the printer offers a characteristic the app can write to. A BLE printer whose service discovery fails, or that has no writable characteristic, answers `false` and the connection is closed.

### Connect deadlines

- Android Bluetooth `onStartConnection` gets `connectTimeoutMs`, which is `connectTimeout` (20 s).
- Android USB `connectPrinter` gets `permissionTimeoutMs`, which is `permissionTimeout` (60 s).

The deadline counts from when the call reaches the plugin, so time spent waiting behind a send counts too. At the deadline the plugin answers `false`, once, and drops the attempt. A Bluetooth attempt is cancelled and the state goes back to none, and a later success of that attempt is closed and ignored. A USB connect stops waiting for the permission dialog. If the user taps "Allow" after the deadline, Android keeps the permission, so the next `connect` uses it without a dialog.

Dart waits `nativeDeadlineMargin` (5 s) longer than the plugin's deadline, so the plugin's answer arrives first. iOS Bluetooth `connect` gives up in Dart after `connectTimeout`.

## Network printing

`TcpPrinterConnector.print(printer, bytes)` opens a socket for one job and closes it when the job ends. Two jobs to two printers never share a socket. Use `print` instead of `connect` and `send`. `connect` and `send` still work, and call `print` for the printer `connect` chose.

For each job, `print`:

1. Connects. It tries `connectAttempts` times (3), with `retryPause` (0.5 s) between tries.
2. Reads every byte the printer sends, so unread bytes cannot make the operating system reset the connection. Bytes that come before the job is sent are thrown away.
3. Writes the job and waits until the operating system took every byte, at most `flushTimeout` (60 s). A timeout, a lost link or a hang-up here gives `PrintCutOff`.
4. Sends the status request and closes its sending side. It waits for the status reply or for the printer to hang up, at most `confirmTimeout` (60 s).
5. Destroys the socket.

`probe(printer)` opens one connection, sends nothing, and closes it. It throws `TcpPrintException` when the printer cannot be reached.

## Troubleshooting

error:'State restoration of CBCentralManager is only allowed for applications that have specified the "bluetooth-central" background mode'
info.plist add:

```
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Allow App use bluetooth?</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>Allow App use bluetooth?</string>
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>bluetooth-peripheral</string>
</array>
```


## Credits
- https://github.com/andrey-ushakov/esc_pos_utils
- https://github.com/bailabs/esc-pos-printer-flutter
- https://github.com/feedmepos/flutter_printer/tree/master/packages/flutter_pos_printer

<p><sub>With contributions from</sub></p>
<p>
  <a href="https://menumiz.com/?utm_source=flutter_pos_printer_platform&utm_medium=readme&utm_campaign=oss-credit">
    <img width="120"
         title="Menumiz — the all-in-one restaurant POS with integrated AI, QR self-ordering, digital menus, and payments"
         alt="Menumiz — best restaurant POS system: affordable cloud restaurant POS &amp; ePOS for small restaurants and cafes, with QR code self-ordering, online ordering, digital menu, e-Waiter AI ordering, table-side ordering, contactless payment, and restaurant management software"
         src="https://menumizpublic.blob.core.windows.net/email/menumiz-transparent-maroon logo.png"/>
  </a>
</p>

## Support me

If you think that this project has helped you with your developments, you can support this project, any support is much appreciated.

[![Paypal](https://raw.githubusercontent.com/arthas1888/flutter_pos_printer_platform/main/btn-sm-paypal-payment.png)](https://www.paypal.com/donate/?hosted_button_id=92HK6VNCK7MUY)