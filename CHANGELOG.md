## 2.0.0

* Breaking: `PrinterConnector.send` and `PrinterManager.send` return a `PrintResult` (`PrintConfirmed`, `PrintUnconfirmed`, `PrintCutOff` or `PrintFailed`) instead of a `bool`. See README.md, "Print results".
* `PrintCutOff` means part of the job may have reached the printer. `PrintFailed` now means no byte left the device. An unknown platform reply is `PrintCutOff`.
* Bluetooth: `connect` gives up after `connectTimeout` (20 s), and a failed Android connect answers `false` instead of never answering. An Android write gives up after `writeTimeout` (60 s). A late status reply to an earlier job no longer confirms the next job. `connect` to a different printer stops the old link first. `BluetoothPrinterConnector.discoverPrinters` collects the scan results instead of waiting forever for a list.
* Android USB: all USB work runs on one thread, so a send never blocks the UI thread. Unplugging another USB device no longer disconnects the printer.
* Network: `TcpPrinterConnector.print` opens a socket for each job and asks the printer for its status after the job. The shared socket is gone. See README.md, "Network printing".
* Android USB: the send waits for every `bulkTransfer`, checks how many bytes each one took, and sends the right length for the last piece. It no longer reports success before the write.
* Android USB: `connect` answers only after Android says whether the app may use the printer. It waits for the permission dialog, at most `UsbPrinterConnector.permissionTimeout` (60 s). Before, it returned true as soon as it asked, so the first job after the dialog failed.
* Android USB: the "connected" toast no longer crashes a send that runs on the worker thread.
* Android: the plugin compiles against SDK 36 with Java 11, so apps that do not raise the plugin's `compileSdk` themselves build again.
* Bluetooth: `disconnect` returns the platform's answer instead of always `false`.
* Android Bluetooth Classic: a failed write fails the job instead of showing a toast.
* Android BLE: the job goes in pieces that fit the MTU, and each piece waits for the printer.
* Android: sends run on a worker thread, not the UI thread.
* Removed the `sendText`, `printText` and `printRawData` Android methods, which no Dart code called.
* Android: each Bluetooth and USB send names the printer its connector connected last (`sendDataByte` gets `address` and `isBle`, `printBytes` gets `vendor` and `product`). When the plugin is connected to a different printer, for example by a second Flutter engine, the send is `PrintFailed` and no byte leaves. See README.md, "Wrong printer".
* Android: Bluetooth `connect`, `disconnect` and the auto-connect retry run on the Bluetooth worker thread, so a connect from another engine waits for the send in progress instead of closing the link in the middle of a write.
* Android: `onStartConnection` gets `connectTimeoutMs` and `connectPrinter` gets `permissionTimeoutMs`. At that deadline the plugin answers `false` once, cancels the Bluetooth attempt and resets the state to none, or stops waiting for the USB permission dialog. Dart waits `nativeDeadlineMargin` (5 s) longer. See README.md, "Connect deadlines".
* Android USB: a `bulkTransfer` that was submitted and failed is `PrintCutOff`, the first one included. `PrintFailed` means no transfer was submitted.
* Android BLE: a piece that got no answer within 5 s closes the BLE connection, and write answers from a closed connection are ignored, so a late answer never counts for a later piece. `connect` answers `true` only when the printer offers a writable characteristic, and `false`, with the connection closed, when service discovery fails or no characteristic is writable.
* Android Bluetooth Classic: a status request that got no answer within `confirmTimeout` closes the link. The job is still `PrintUnconfirmed`.
* Windows USB: a full spooler write is `PrintUnconfirmed`, never `PrintConfirmed`. A partial write cancels the job instead of ending it and is `PrintCutOff`. A job the spooler never started, or that got no byte, is `PrintFailed`. The `printBytes` reply is a map with `outcome` and `reason`. `connect` closes the printer handle it opened before.

## 1.1.0

* Toast msgs english locale default

## 1.0.12

* Resolve minor bug [Android] 12

## 1.0.11

* Resolve minor bug dependecies

## 1.0.10

* Now android supports targetSdkVersion 31

## 1.0.9

* Resolve minor bug [Android] connection

## 1.0.8

* Resolve minor bug [Android] connection

## 1.0.6

* Resolve minor bug [Android] connection

## 1.0.6

* Resolve minor bug windows printer

## 1.0.5

* Get current status bt

## 1.0.4

* Solved Bug windows: USB

## 1.0.3

* Bug android notify events: Bluetooth 

## 1.0.2

* Bug android connection interface: USB 

## 1.0.1

* How to use it.

## 1.0.0

* Initial release.
