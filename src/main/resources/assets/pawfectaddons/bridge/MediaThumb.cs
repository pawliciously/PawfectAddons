using System;
using System.IO;
using System.Threading;
using Windows.Foundation;
using Windows.Media.Control;
using Windows.Storage.Streams;

public static class PawfectMedia {

    static T Wait<T>(IAsyncOperation<T> operation) {
        var done = new ManualResetEventSlim(false);
        operation.Completed = (op, status) => done.Set();
        if (!done.Wait(6000)) return default(T);
        return operation.Status == AsyncStatus.Completed ? operation.GetResults() : default(T);
    }

    static GlobalSystemMediaTransportControlsSessionMediaProperties Find(string appId, string title) {
        var manager = Wait(GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
        if (manager == null) return null;
        GlobalSystemMediaTransportControlsSessionMediaProperties sameApp = null;
        foreach (var session in manager.GetSessions()) {
            if (session.SourceAppUserModelId != appId) continue;
            var properties = Wait(session.TryGetMediaPropertiesAsync());
            if (properties == null) continue;
            if (properties.Title == title) return properties;
            if (sameApp == null) sameApp = properties;
        }
        return sameApp;
    }

    static string Capture(string path, string appId, string title) {
        var properties = Find(appId, title);
        if (properties == null) return "no-session";
        if (properties.Thumbnail == null) return "no-thumb";

        var stream = Wait(properties.Thumbnail.OpenReadAsync());
        if (stream == null) return "no-stream";

        uint size = (uint)stream.Size;
        if (size == 0) return "no-thumb";

        var reader = new DataReader(stream.GetInputStreamAt(0));
        var load = reader.LoadAsync(size);
        var loaded = new ManualResetEventSlim(false);
        load.Completed = (op, status) => loaded.Set();
        if (!loaded.Wait(6000)) return "load-timeout";

        var bytes = new byte[size];
        reader.ReadBytes(bytes);
        File.WriteAllBytes(path, bytes);

        uint hash = 2166136261;
        foreach (var b in bytes) { hash ^= b; hash *= 16777619; }
        return "ok:" + hash.ToString("x8");
    }

    public static string SaveThumb(string path, string appId, string title) {
        string result = "unset";
        var worker = new Thread(delegate() {
            try { result = Capture(path, appId, title); } catch (Exception error) { result = "error: " + error.Message; }
        });
        worker.SetApartmentState(ApartmentState.MTA);
        worker.IsBackground = true;
        worker.Start();
        if (!worker.Join(20000)) return "timeout";
        return result;
    }
}
