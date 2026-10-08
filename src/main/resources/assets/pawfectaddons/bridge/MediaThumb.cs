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

    // A capture can take seconds while the player hands over its artwork, so it runs on its own
    // thread. The bridge starts one and asks for the result on a later pass, and never waits.
    static Thread worker;
    static DateTime startedAt;
    static volatile string finished;

    const int ABANDON_SECONDS = 30;

    public static bool Busy {
        get { return worker != null && worker.IsAlive && (DateTime.UtcNow - startedAt).TotalSeconds < ABANDON_SECONDS; }
    }

    public static bool StartThumb(string path, string appId, string title) {
        if (Busy) return false;
        // A capture stuck past the limit is left to finish on its own; its result is dropped.
        finished = null;
        var run = new Thread(delegate() {
            string result;
            try { result = Capture(path, appId, title); } catch (Exception error) { result = "error: " + error.Message; }
            if (Thread.CurrentThread == worker) finished = result;
        });
        run.SetApartmentState(ApartmentState.MTA);
        run.IsBackground = true;
        worker = run;
        startedAt = DateTime.UtcNow;
        run.Start();
        return true;
    }

    /** The finished capture's result once, or null while one is running or none was started. */
    public static string PollThumb() {
        var result = finished;
        if (result == null) return null;
        finished = null;
        return result;
    }
}
