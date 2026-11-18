// Captures audio and writes raw PCM (s16le, 48000 Hz, stereo) to stdout for the mod's screen sharing.
//   cms-screen-audio list
//   cms-screen-audio include <pid>
//   cms-screen-audio exclude <pid>
//   cms-screen-audio device <endpoint id>
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <initguid.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <propsys.h>
#include <cwchar>
#include <cwctype>
#include <cstdio>
#include <cstdlib>

// MinGW lacks audioclientactivationparams.h.
enum AUDIOCLIENT_ACTIVATION_TYPE { AUDIOCLIENT_ACTIVATION_TYPE_DEFAULT = 0, AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK = 1 };
enum PROCESS_LOOPBACK_MODE {
    PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE = 0,
    PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE = 1
};
struct AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS { DWORD TargetProcessId; PROCESS_LOOPBACK_MODE ProcessLoopbackMode; };
struct AUDIOCLIENT_ACTIVATION_PARAMS {
    AUDIOCLIENT_ACTIVATION_TYPE ActivationType;
    union { AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS ProcessLoopbackParams; };
};
static const wchar_t VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK[] = L"VAD\\Process_Loopback";
DEFINE_PROPERTYKEY(PKEY_Device_FriendlyName_, 0xa45c254e, 0xdf1c, 0x4efd, 0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0, 14);

static const UINT32 kRate = 48000, kChannels = 2, kBits = 16, kFrameBytes = kChannels * kBits / 8;
static const REFERENCE_TIME kBuffer = 200000; // 20 ms in 100 ns units

// The first caller picks the exit code, so a stdin EOF racing a failure cannot report success.
[[noreturn]] static void quit(UINT code, const char *why = nullptr) {
    static LONG exiting = 0;
    if (InterlockedExchange(&exiting, 1)) Sleep(INFINITE);
    if (why) fprintf(stderr, "%s\n", why);
    fflush(stderr);
    // Audio and COM threads may hold locks; skip DLL teardown so exit never hangs.
    TerminateProcess(GetCurrentProcess(), code);
    ExitProcess(code);
}

[[noreturn]] static void fail(const char *why) { quit(1, why); }

static bool writeAll(HANDLE out, const void *data, DWORD size) {
    const char *p = static_cast<const char *>(data);
    while (size) {
        DWORD n = 0;
        if (!WriteFile(out, p, size, &n, nullptr) || !n) return false;
        p += n;
        size -= n;
    }
    return true;
}

// The parent closing stdin (or dying) is the only shutdown signal; kill hard because the main
// thread may be blocked on a full stdout pipe or on an audio event that never fires.
static DWORD WINAPI watchStdin(void *) {
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    char buf[256];
    DWORD n;
    while (ReadFile(in, buf, sizeof buf, &n, nullptr) && n) {}
    quit(0);
}

static WAVEFORMATEX pcmFormat() {
    WAVEFORMATEX f{};
    f.wFormatTag = WAVE_FORMAT_PCM;
    f.nChannels = kChannels;
    f.nSamplesPerSec = kRate;
    f.wBitsPerSample = kBits;
    f.nBlockAlign = kFrameBytes;
    f.nAvgBytesPerSec = kRate * kFrameBytes;
    return f;
}

static IMMDeviceEnumerator *enumerator() {
    IMMDeviceEnumerator *e = nullptr;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), (void **)&e)))
        fail("Cannot open the Windows audio device list");
    return e;
}

static int list() {
    IMMDeviceCollection *devices = nullptr;
    if (FAILED(enumerator()->EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE, &devices)))
        fail("Cannot list capture devices");
    UINT count = 0;
    devices->GetCount(&count);
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    for (UINT i = 0; i < count; i++) {
        IMMDevice *device = nullptr;
        LPWSTR id = nullptr;
        if (FAILED(devices->Item(i, &device)) || FAILED(device->GetId(&id))) continue;
        PROPVARIANT name;
        PropVariantInit(&name);
        IPropertyStore *props = nullptr;
        if (SUCCEEDED(device->OpenPropertyStore(STGM_READ, &props))) props->GetValue(PKEY_Device_FriendlyName_, &name);
        const wchar_t *friendly = name.vt == VT_LPWSTR ? name.pwszVal : L"";
        wchar_t line[2048];
        int len = swprintf(line, 2048, L"%ls\t%ls\n", id, friendly);
        if (len > 0) {
            char utf8[8192];
            int bytes = WideCharToMultiByte(CP_UTF8, 0, line, len, utf8, sizeof utf8, nullptr, nullptr);
            if (bytes > 0 && !writeAll(out, utf8, bytes)) return 1;
        }
        PropVariantClear(&name);
        CoTaskMemFree(id);
    }
    return 0;
}

struct ActivateHandler : IActivateAudioInterfaceCompletionHandler, IAgileObject {
    HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    // Activation calls back on an MTA worker thread, so the handler must be agile.
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IActivateAudioInterfaceCompletionHandler))
            *out = static_cast<IActivateAudioInterfaceCompletionHandler *>(this);
        else if (riid == __uuidof(IAgileObject))
            *out = static_cast<IAgileObject *>(this);
        else {
            *out = nullptr;
            return E_NOINTERFACE;
        }
        return S_OK;
    }
    // Lives for the whole process.
    ULONG STDMETHODCALLTYPE AddRef() override { return 2; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation *) override {
        SetEvent(done);
        return S_OK;
    }
};

static DWORD windowsBuild() {
    // GetVersionEx reports 6.2 to unmanifested programs.
    OSVERSIONINFOW v{sizeof v};
    auto rtlGetVersion = (LONG(WINAPI *)(OSVERSIONINFOW *))GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion");
    return rtlGetVersion && rtlGetVersion(&v) == 0 ? v.dwBuildNumber : 0;
}

static IAudioClient *processLoopback(DWORD pid, PROCESS_LOOPBACK_MODE mode) {
    if (windowsBuild() < 20348) fail("Process loopback needs Windows 10 build 20348 or later");
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    // Access denied still means the process exists.
    if (!process && GetLastError() != ERROR_ACCESS_DENIED) fail("No such process");
    DWORD exitCode = 0;
    if (process && GetExitCodeProcess(process, &exitCode) && exitCode != STILL_ACTIVE) fail("No such process");
    if (process) CloseHandle(process);

    AUDIOCLIENT_ACTIVATION_PARAMS params{};
    params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    params.ProcessLoopbackParams = {pid, mode};
    PROPVARIANT var;
    PropVariantInit(&var);
    var.vt = VT_BLOB;
    var.blob.cbSize = sizeof params;
    var.blob.pBlobData = reinterpret_cast<BYTE *>(&params);

    static ActivateHandler handler;
    IActivateAudioInterfaceAsyncOperation *op = nullptr;
    if (FAILED(ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient), &var, &handler, &op)))
        fail("Cannot start process loopback capture");
    WaitForSingleObject(handler.done, INFINITE);
    HRESULT hr = E_FAIL;
    IUnknown *unknown = nullptr;
    if (FAILED(op->GetActivateResult(&hr, &unknown)) || FAILED(hr) || !unknown) fail("Cannot start process loopback capture");
    IAudioClient *client = nullptr;
    unknown->QueryInterface(__uuidof(IAudioClient), (void **)&client);
    WAVEFORMATEX format = pcmFormat();
    // Process loopback has no mix format; it converts to whatever format is asked for here.
    if (!client || FAILED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                             kBuffer, 0, &format, nullptr)))
        fail("Cannot start process loopback capture");
    return client;
}

static IAudioClient *captureDevice(const wchar_t *id) {
    IMMDevice *device = nullptr;
    if (FAILED(enumerator()->GetDevice(id, &device))) fail("No such capture device");
    IMMEndpoint *endpoint = nullptr;
    EDataFlow flow = eRender;
    if (FAILED(device->QueryInterface(__uuidof(IMMEndpoint), (void **)&endpoint)) || FAILED(endpoint->GetDataFlow(&flow)) || flow != eCapture)
        fail("No such capture device");
    DWORD state = 0;
    if (FAILED(device->GetState(&state)) || state != DEVICE_STATE_ACTIVE) fail("Capture device is not active");
    IAudioClient *client = nullptr;
    if (FAILED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, (void **)&client))) fail("Cannot open capture device");
    WAVEFORMATEX format = pcmFormat();
    if (FAILED(client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                  AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                  kBuffer, 0, &format, nullptr)))
        fail("Cannot open capture device");
    return client;
}

static int capture(IAudioClient *client) {
    HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    IAudioCaptureClient *capture = nullptr;
    if (FAILED(client->SetEventHandle(ready)) || FAILED(client->GetService(__uuidof(IAudioCaptureClient), (void **)&capture)) ||
        FAILED(client->Start()))
        fail("Cannot start audio capture");
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    static char zeros[kRate * kFrameBytes]; // one second of silence
    for (;;) {
        // No event while nothing plays is normal; the stdin watcher handles shutdown.
        WaitForSingleObject(ready, 1000);
        for (;;) {
            BYTE *data;
            UINT32 frames;
            DWORD flags;
            HRESULT hr = capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr);
            if (hr == AUDCLNT_S_BUFFER_EMPTY) break;
            if (hr == AUDCLNT_E_DEVICE_INVALIDATED) fail("The capture device was removed");
            if (FAILED(hr)) fail("Audio capture failed");
            DWORD bytes = frames * kFrameBytes;
            bool ok = true;
            if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
                for (DWORD left = bytes; ok && left;) {
                    DWORD n = left < sizeof zeros ? left : sizeof zeros;
                    ok = writeAll(out, zeros, n);
                    left -= n;
                }
            } else {
                ok = writeAll(out, data, bytes);
            }
            capture->ReleaseBuffer(frames);
            if (!ok) return 0; // reader went away
        }
    }
}

static DWORD parsePid(const wchar_t *s) {
    wchar_t *end;
    unsigned long pid = wcstoul(s, &end, 10);
    if (!*s || *end || !iswdigit(*s)) fail("The process id must be a number");
    return pid;
}

int wmain(int argc, wchar_t **argv) {
    const wchar_t *cmd = argc >= 2 ? argv[1] : L"";
    bool isList = !wcscmp(cmd, L"list") && argc == 2;
    if (!isList && (argc != 3 || (wcscmp(cmd, L"include") && wcscmp(cmd, L"exclude") && wcscmp(cmd, L"device"))))
        fail("Usage: cms-screen-audio list | include <pid> | exclude <pid> | device <endpoint id>");
    if (FAILED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) fail("Cannot initialize COM");
    if (isList) quit(list());
    CloseHandle(CreateThread(nullptr, 0, watchStdin, nullptr, 0, nullptr));
    IAudioClient *client = !wcscmp(cmd, L"device") ? captureDevice(argv[2])
                         : processLoopback(parsePid(argv[2]), !wcscmp(cmd, L"include") ? PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE
                                                                                      : PROCESS_LOOPBACK_MODE_EXCLUDE_TARGET_PROCESS_TREE);
    quit(capture(client));
}
