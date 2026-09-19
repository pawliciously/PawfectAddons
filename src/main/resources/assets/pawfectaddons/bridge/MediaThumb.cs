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

    static string Capture(string path) {
        var manager = Wait(GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
        if (manager == null) return "no-manager";

        var session = manager.GetCurrentSession();
        if (session == null) return "no-session";

        var properties = Wait(session.TryGetMediaPropertiesAsync());
        if (properties == null || properties.Thumbnail == null) return "no-thumb";

        var stream = Wait(properties.Thumbnail.OpenReadAsync());
        if (stream == null) return "no-stream";

        uint size = (uint)stream.Size;
        if (size == 0) return "empty";

        var reader = new DataReader(stream.GetInputStreamAt(0));
        var load = reader.LoadAsync(size);
        var loaded = new ManualResetEventSlim(false);
        load.Completed = (op, status) => loaded.Set();
        if (!loaded.Wait(6000)) return "load-timeout";

        var bytes = new byte[size];
        reader.ReadBytes(bytes);
        File.WriteAllBytes(path, bytes);
        return "ok:" + bytes.Length;
    }

    public static string SaveThumb(string path) {
        string result = "unset";
        var worker = new Thread(delegate() {
            try { result = Capture(path); } catch (Exception error) { result = "error: " + error.Message; }
        });
        worker.SetApartmentState(ApartmentState.MTA);
        worker.IsBackground = true;
        worker.Start();
        if (!worker.Join(20000)) return "timeout";
        return result;
    }
}
