#include <windows.h>
#include <objbase.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <tchar.h>
#include <fstream>

#include "printer.h"
#include "utils.hpp"

HANDLE PrintManager::_hPrinter;

std::vector<Printer> PrintManager::listPrinters()
{
    LPTSTR defaultPrinter;
    DWORD size = 0;
    GetDefaultPrinter(nullptr, &size);

    defaultPrinter = static_cast<LPTSTR>(malloc(size * sizeof(TCHAR)));
    if (!GetDefaultPrinter(defaultPrinter, &size))
    {
        size = 0;
    }

    auto printers = std::vector<Printer>{};

    DWORD needed = 0,
          returned = 0,
          flags = PRINTER_ENUM_LOCAL | PRINTER_ENUM_CONNECTIONS;

    EnumPrintersW(flags, nullptr, 2, nullptr, 0, &needed, &returned);

    auto buffer = (PRINTER_INFO_2 *)malloc(needed);
    if (!buffer)
    {
        return printers;
    }

    auto result = EnumPrintersW(flags, nullptr, 2, (LPBYTE)buffer, needed, &needed,
                                &returned);

    if (!result)
    {
        free(buffer);
        return printers;
    }

    for (DWORD i = 0; i < returned; i++)
    {
        printers.push_back(Printer{
            toUtf8(buffer[i].pPrinterName),
            toUtf8(buffer[i].pDriverName),
            size > 0 && _tcsncmp(buffer[i].pPrinterName, defaultPrinter, size) == 0, // if this is the defaultprinter
            (buffer[i].Status &
             (PRINTER_STATUS_NOT_AVAILABLE | PRINTER_STATUS_ERROR |
              PRINTER_STATUS_OFFLINE | PRINTER_STATUS_PAUSED)) == 0});
    }

    free(buffer);
    free(defaultPrinter);
    return printers;
}

BOOL PrintManager::pickPrinter(std::string printerName)
{
    close();
    return OpenPrinterW((LPWSTR)fromUtf8(printerName).c_str(), &_hPrinter, NULL);
}

static std::string withError(const std::string &reason, DWORD error)
{
    return reason + " (Windows error " + std::to_string(error) + ")";
}

void PrintManager::abandonJob()
{
    AbortPrinter(_hPrinter);
    // AbortPrinter has no effect on a printer that does not spool, so the handle is closed to drop the started document.
    close();
}

SpoolerOutcome PrintManager::printBytes(const std::vector<uint8_t> &data)
{
    if (_hPrinter == NULL || _hPrinter == INVALID_HANDLE_VALUE)
    {
        return SpoolerOutcome{"failed", "No Windows printer is open. Connect to the printer first."};
    }

    DOC_INFO_1W docInfo;
    docInfo.pDocName = L"FeedMe POS Print Job";
    docInfo.pOutputFile = NULL;
    docInfo.pDatatype = L"RAW";

    if (StartDocPrinterW(_hPrinter, 1, (LPBYTE)&docInfo) == 0)
    {
        return SpoolerOutcome{"failed", withError("Windows print spooler did not start the job", GetLastError())};
    }
    if (!StartPagePrinter(_hPrinter))
    {
        DWORD error = GetLastError();
        abandonJob();
        return SpoolerOutcome{"failed", withError("Windows print spooler did not start the page", error)};
    }

    DWORD written = 0;
    BOOL wrote = WritePrinter(_hPrinter, (LPVOID)data.data(), (DWORD)data.size(), &written);
    if (!wrote || written != data.size())
    {
        DWORD error = GetLastError();
        abandonJob();
        if (written == 0)
        {
            return SpoolerOutcome{"failed", withError("Windows print spooler took no byte of the job", error)};
        }
        return SpoolerOutcome{
            "cutOff",
            withError("Windows print spooler took " + std::to_string(written) + " of " + std::to_string(data.size()) +
                          " bytes, then the job was cancelled and the printer closed. The printer may have printed part of it",
                      error)};
    }

    if (!EndPagePrinter(_hPrinter))
    {
        DWORD error = GetLastError();
        abandonJob();
        return SpoolerOutcome{"cutOff", withError("Windows print spooler took every byte but could not end the page, so the job was cancelled", error)};
    }
    if (!EndDocPrinter(_hPrinter))
    {
        DWORD error = GetLastError();
        abandonJob();
        return SpoolerOutcome{"cutOff", withError("Windows print spooler took every byte but could not end the job, so the job was cancelled", error)};
    }
    return SpoolerOutcome{"sent", "Windows print spooler took every byte. The printer gives no confirmation through the spooler."};
}

BOOL PrintManager::close()
{
    if (_hPrinter == NULL || _hPrinter == INVALID_HANDLE_VALUE)
        return false;

    BOOL closed = ClosePrinter(_hPrinter);
    _hPrinter = NULL;
    return closed;
}
