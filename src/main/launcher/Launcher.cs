using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

class Launcher {
    static void Main() {
        string dir  = AppDomain.CurrentDomain.BaseDirectory;
        string java = Path.Combine(dir, "jre", "bin", "java.exe");
        string jar  = Path.Combine(dir, "app", "btc-tracking.jar");

        if (!File.Exists(java)) {
            System.Windows.Forms.MessageBox.Show(
                "JRE not found at: " + java,
                "BtcTracking – Error");
            return;
        }
        if (!File.Exists(jar)) {
            System.Windows.Forms.MessageBox.Show(
                "btc-tracking.jar not found at: " + jar,
                "BtcTracking – Error");
            return;
        }

        var p = new Process();
        p.StartInfo.FileName        = java;
        p.StartInfo.Arguments       = $"-Xmx256m -Dfile.encoding=UTF-8 -jar \"{jar}\" --depot.db=h2file";
        p.StartInfo.UseShellExecute = false;
        p.StartInfo.CreateNoWindow  = true;
        p.Start();

        Thread.Sleep(5000);
        Process.Start(new ProcessStartInfo("http://localhost:8080/btc-tracking") {
            UseShellExecute = true
        });
    }
}
