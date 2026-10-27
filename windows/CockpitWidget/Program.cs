// Cockpit PEA — widget de bureau Windows.
// Lit les données du Cockpit via la synchro chiffrée (gist privé GitHub), recalcule la valeur avec les cours
// relevés toutes les 30 min (journal/raw.json) et s'affiche en petit ou grand format sur le bureau.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using Microsoft.Win32;
using WF = System.Windows.Forms;

namespace CockpitWidget
{
    // ------------------------------------------------------------------ configuration
    class Config
    {
        public string Token = "";
        public string Code = "";
        public double Left = double.NaN, Top = double.NaN;
        public bool Large = true, Topmost = false, Hidden = false;
        public int Period = 1;

        static string Dir => System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CockpitWidget");
        static string FilePath => System.IO.Path.Combine(Dir, "config.json");
        public static string CachePath => System.IO.Path.Combine(Dir, "cache.json");

        static string Protect(string s) => string.IsNullOrEmpty(s) ? "" :
            Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(s), null, DataProtectionScope.CurrentUser));
        static string Unprotect(string s)
        {
            try { return string.IsNullOrEmpty(s) ? "" : Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(s), null, DataProtectionScope.CurrentUser)); }
            catch { return ""; }
        }

        public static Config Load()
        {
            var c = new Config();
            try
            {
                if (!File.Exists(FilePath)) return c;
                var d = (Dictionary<string, object>)new JavaScriptSerializer().DeserializeObject(File.ReadAllText(FilePath));
                c.Token = Unprotect(J.Str(d, "token"));
                c.Code = Unprotect(J.Str(d, "code"));
                c.Left = J.Num(d, "left", double.NaN);
                c.Top = J.Num(d, "top", double.NaN);
                c.Large = J.Bool(d, "large", true);
                c.Topmost = J.Bool(d, "topmost", false);
                c.Hidden = J.Bool(d, "hidden", false);
                c.Period = (int)J.Num(d, "period", 1);
            }
            catch { }
            return c;
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var d = new Dictionary<string, object>
                {
                    ["token"] = Protect(Token), ["code"] = Protect(Code),
                    ["left"] = double.IsNaN(Left) ? (object)null : Left, ["top"] = double.IsNaN(Top) ? (object)null : Top,
                    ["large"] = Large, ["topmost"] = Topmost, ["hidden"] = Hidden, ["period"] = Period
                };
                File.WriteAllText(FilePath, new JavaScriptSerializer().Serialize(d));
            }
            catch { }
        }

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        public static bool StartupEnabled
        {
            get { try { using (var k = Registry.CurrentUser.OpenSubKey(RunKey)) return k?.GetValue("CockpitWidget") != null; } catch { return false; } }
            set
            {
                try
                {
                    using (var k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                    {
                        if (value) k.SetValue("CockpitWidget", "\"" + Process.GetCurrentProcess().MainModule.FileName + "\"");
                        else k.DeleteValue("CockpitWidget", false);
                    }
                }
                catch { }
            }
        }
    }

    // ------------------------------------------------------------------ lecture JSON
    static class J
    {
        public static object Get(object o, string k) { var d = o as IDictionary<string, object>; return d != null && d.ContainsKey(k) ? d[k] : null; }
        public static string Str(object o, string k) { var v = Get(o, k); return v == null ? "" : Convert.ToString(v, CultureInfo.InvariantCulture); }
        public static double Num(object o, string k, double def = 0)
        {
            var v = Get(o, k);
            if (v == null) return def;
            try { return Convert.ToDouble(v, CultureInfo.InvariantCulture); } catch { return def; }
        }
        public static bool Bool(object o, string k, bool def = false) { var v = Get(o, k); return v is bool b ? b : def; }
        public static object[] Arr(object o, string k) { var v = Get(o, k) as object[]; if (v != null) return v; var l = Get(o, k) as ArrayList; return l != null ? l.ToArray() : new object[0]; }
        public static double D(object v) { try { return Convert.ToDouble(v, CultureInfo.InvariantCulture); } catch { return 0; } }
        public static object Parse(string s) { var js = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 200 }; return js.DeserializeObject(s); }
    }

    // ------------------------------------------------------------------ déchiffrement (identique au Cockpit : PBKDF2-SHA256 + AES-256-GCM)
    static class Crypto
    {
        public static string Norm(string c)
        {
            var s = (c ?? "").Normalize(NormalizationForm.FormKC).ToLowerInvariant();
            var sb = new StringBuilder();
            foreach (var ch in s)
            {
                if (char.IsWhiteSpace(ch) || ch == ' ') continue;
                if ((ch >= '‐' && ch <= '―') || ch == '−' || ch == '_') { sb.Append('-'); continue; }
                sb.Append(ch);
            }
            return sb.ToString();
        }

        public static byte[] Key(string code, byte[] salt)
        {
            using (var kdf = new Rfc2898DeriveBytes(Encoding.UTF8.GetBytes(Norm(code)), salt, 200000, HashAlgorithmName.SHA256))
                return kdf.GetBytes(32);
        }

        static byte[] Ecb(byte[] key, byte[] block)
        {
            using (var aes = Aes.Create())
            {
                aes.Key = key; aes.Mode = CipherMode.ECB; aes.Padding = PaddingMode.None;
                using (var e = aes.CreateEncryptor()) return e.TransformFinalBlock(block, 0, 16);
            }
        }

        static void Mul(ref ulong xh, ref ulong xl, ulong hh, ulong hl)
        {
            ulong zh = 0, zl = 0, vh = hh, vl = hl;
            for (int i = 0; i < 128; i++)
            {
                bool bit = i < 64 ? ((xh >> (63 - i)) & 1) != 0 : ((xl >> (127 - i)) & 1) != 0;
                if (bit) { zh ^= vh; zl ^= vl; }
                bool lsb = (vl & 1) != 0;
                vl = (vl >> 1) | (vh << 63);
                vh >>= 1;
                if (lsb) vh ^= 0xE100000000000000UL;
            }
            xh = zh; xl = zl;
        }

        static ulong BE(byte[] b, int o) { ulong r = 0; for (int i = 0; i < 8; i++) r = (r << 8) | (o + i < b.Length ? b[o + i] : (byte)0); return r; }
        static void Inc32(byte[] c) { for (int i = 15; i >= 12; i--) if (++c[i] != 0) break; }

        public static string Decrypt(string saltB64, string ivB64, string ctB64, string code)
        {
            var salt = Convert.FromBase64String(saltB64);
            var iv = Convert.FromBase64String(ivB64);
            var all = Convert.FromBase64String(ctB64);
            if (iv.Length != 12 || all.Length < 16) throw new CryptographicException("format");
            var key = Key(code, salt);
            int n = all.Length - 16;
            var ct = new byte[n]; Buffer.BlockCopy(all, 0, ct, 0, n);
            var tag = new byte[16]; Buffer.BlockCopy(all, n, tag, 0, 16);

            var h = Ecb(key, new byte[16]);
            ulong hh = BE(h, 0), hl = BE(h, 8), xh = 0, xl = 0;
            for (int o = 0; o < n; o += 16)
            {
                var blk = new byte[16]; Buffer.BlockCopy(ct, o, blk, 0, Math.Min(16, n - o));
                xh ^= BE(blk, 0); xl ^= BE(blk, 8);
                Mul(ref xh, ref xl, hh, hl);
            }
            xh ^= 0UL; xl ^= (ulong)n * 8UL;
            Mul(ref xh, ref xl, hh, hl);

            var j0 = new byte[16]; Buffer.BlockCopy(iv, 0, j0, 0, 12); j0[15] = 1;
            var ej0 = Ecb(key, j0);
            var calc = new byte[16];
            for (int i = 0; i < 8; i++) { calc[i] = (byte)(xh >> (56 - 8 * i)); calc[8 + i] = (byte)(xl >> (56 - 8 * i)); }
            int diff = 0;
            for (int i = 0; i < 16; i++) diff |= (calc[i] ^ ej0[i]) ^ tag[i];
            if (diff != 0) throw new CryptographicException("Code incorrect");

            var pt = new byte[n];
            var ctr = (byte[])j0.Clone();
            for (int o = 0; o < n; o += 16)
            {
                Inc32(ctr);
                var ks = Ecb(key, ctr);
                for (int i = 0; i < 16 && o + i < n; i++) pt[o + i] = (byte)(ct[o + i] ^ ks[i]);
            }
            return Encoding.UTF8.GetString(pt);
        }
    }

    // ------------------------------------------------------------------ données
    class Line { public string K, N, Y, Color; public double Q, Px, Prev; }
    class Model
    {
        public bool Privacy;
        public double Value, Verse, Gain, DayAbs, DayPct, Cash;
        public List<Line> Lines = new List<Line>();
        public List<Tuple<DateTime, double, double>> Hist = new List<Tuple<DateTime, double, double>>();
        public double ApCur, ApTgt, MsCur, MsTgt;
        public string Next = "", Alert = "", Brief = "", From = "";
        public DateTime SyncAt, PriceAt;
    }

    static class Data
    {
        const string Base = "https://tommy22032002.github.io/COCKPIT-/";
        static readonly HttpClient Http = MakeClient();
        static HttpClient MakeClient()
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            var h = new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
            h.DefaultRequestHeaders.UserAgent.ParseAdd("CockpitWidget/1.0");
            return h;
        }
        static string gistId;

        static async Task<string> Gh(string path, string token)
        {
            var req = new HttpRequestMessage(HttpMethod.Get, "https://api.github.com" + path);
            req.Headers.TryAddWithoutValidation("Authorization", "token " + token);
            req.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
            var r = await Http.SendAsync(req).ConfigureAwait(false);
            if ((int)r.StatusCode == 401) throw new Exception("Clé GitHub refusée : vérifie-la dans les paramètres.");
            if (!r.IsSuccessStatusCode) throw new Exception("GitHub ne répond pas (" + (int)r.StatusCode + ").");
            return await r.Content.ReadAsStringAsync().ConfigureAwait(false);
        }

        /// <summary>Télécharge et déchiffre le paquet de synchro. Renvoie l'objet « snapshot » complet.</summary>
        public static async Task<object> LoadSync(Config c)
        {
            if (string.IsNullOrEmpty(c.Token) || string.IsNullOrEmpty(c.Code)) throw new Exception("Configure ta clé GitHub et ton code (clic droit → Paramètres).");
            if (gistId == null)
            {
                var list = J.Parse(await Gh("/gists?per_page=100", c.Token).ConfigureAwait(false)) as object[];
                var g = (list ?? new object[0]).FirstOrDefault(x => J.Str(x, "description") == "cockpit-pea-sync");
                if (g == null) throw new Exception("Aucune synchro trouvée : active-la d'abord dans le Cockpit (Mettre à jour → Synchro).");
                gistId = J.Str(g, "id");
            }
            var gist = J.Parse(await Gh("/gists/" + gistId, c.Token).ConfigureAwait(false));
            var f = J.Get(J.Get(gist, "files"), "cockpit.sync");
            string content = J.Str(f, "content");
            if (J.Bool(f, "truncated")) content = await Http.GetStringAsync(J.Str(f, "raw_url")).ConfigureAwait(false);
            var p = J.Parse(content);
            string plain;
            try { plain = Crypto.Decrypt(J.Str(p, "salt"), J.Str(p, "iv"), J.Str(p, "ct"), c.Code); }
            catch (CryptographicException) { throw new Exception("Code de synchro incorrect : mets le même que dans le Cockpit."); }
            var snap = J.Parse(plain) as Dictionary<string, object>;
            if (snap != null) { snap["__at"] = J.Str(p, "at"); snap["__dev"] = J.Str(p, "dev"); }
            return snap;
        }

        public static async Task<object> LoadRaw()
        {
            try { return J.Parse(await Http.GetStringAsync(Base + "journal/raw.json?t=" + DateTime.UtcNow.Ticks).ConfigureAwait(false)); } catch { return null; }
        }
        public static async Task<string> LoadBriefTitle()
        {
            try
            {
                var b = J.Parse(await Http.GetStringAsync(Base + "journal/brief.json?t=" + DateTime.UtcNow.Ticks).ConfigureAwait(false));
                return J.Str(b, "title");
            }
            catch { return ""; }
        }

        public static Model Build(object snap, object raw, string brief)
        {
            var m = new Model();
            var mini = J.Get(snap, "_mini");
            if (mini == null) throw new Exception("Ouvre le Cockpit une fois sur ton téléphone ou ton PC pour envoyer le résumé du widget.");
            m.Privacy = J.Bool(mini, "privacy");
            m.Verse = J.Num(mini, "verse"); m.Cash = J.Num(mini, "cash");
            m.ApCur = J.Num(mini, "apCur"); m.ApTgt = Math.Max(1, J.Num(mini, "apTgt", 50000));
            m.MsCur = J.Num(mini, "msCur"); m.MsTgt = Math.Max(1, J.Num(mini, "msTgt", 100000));
            m.Next = J.Str(mini, "next"); m.Alert = J.Str(mini, "alert"); m.Brief = brief ?? "";
            m.From = J.Str(snap, "__dev");
            DateTime.TryParse(J.Str(snap, "__at"), CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out m.SyncAt);
            var q = J.Get(raw, "q");
            double pos = 0, day = 0, baseV = 0;
            foreach (var o in J.Arr(mini, "lines"))
            {
                var l = new Line { K = J.Str(o, "k"), N = J.Str(o, "n"), Y = J.Str(o, "y"), Color = J.Str(o, "c"), Q = J.Num(o, "q"), Px = J.Num(o, "px") };
                if (string.IsNullOrEmpty(l.Y)) { var s = J.Str(o, "s"); l.Y = s.EndsWith(".PAR") ? s.Substring(0, s.Length - 4) + ".PA" : s; }
                var qq = J.Get(q, l.Y);
                if (qq != null && J.Num(qq, "p") > 0) { l.Px = J.Num(qq, "p"); l.Prev = J.Num(qq, "prev"); }
                pos += l.Q * l.Px;
                if (l.Prev > 0) { day += l.Q * (l.Px - l.Prev); baseV += l.Q * l.Prev; }
                m.Lines.Add(l);
            }
            m.Value = pos + m.Cash;
            if (baseV > 0) { m.DayAbs = day; m.DayPct = day / baseV; } else { m.DayAbs = J.Num(mini, "dayAbs"); m.DayPct = J.Num(mini, "dayPct"); }
            m.Gain = m.Value - m.Verse;
            var at = J.Num(raw, "at");
            m.PriceAt = at > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)at).LocalDateTime : DateTime.Now;
            foreach (var h in J.Arr(mini, "hist"))
            {
                var a = h as object[]; if (a == null || a.Length < 3) continue;
                if (DateTime.TryParseExact(Convert.ToString(a[0]), "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d))
                    m.Hist.Add(Tuple.Create(d, J.D(a[1]), J.D(a[2])));
            }
            var today = DateTime.Today;
            m.Hist.RemoveAll(t => t.Item1 >= today);
            m.Hist.Add(Tuple.Create(today, m.Value, m.Verse));
            return m;
        }
    }

    // ------------------------------------------------------------------ fenêtre du widget
    class Widget : Window
    {
        static readonly Brush Ink = B("#E8ECF3"), Muted = B("#8B95A7"), Faint = B("#5D6677"), Gain = B("#3FBF8F"), Loss = B("#E07C86"),
            SoftGain = B("#8CCFB4"), SoftLoss = B("#C9A3A8"), Accent = B("#6EA8FE"), Track = B("#2A313D");
        static readonly string[] Pal = { "#7EA6FF", "#B79CF7", "#4FCFA8", "#F38DB6", "#F2B65C", "#9AA2B1", "#7BD389" };
        static readonly string[] Periods = { "1 mois", "3 mois", "1 an", "Tout" };
        static readonly int[] PeriodDays = { 31, 92, 366, 100000 };
        static SolidColorBrush B(string h) { var b = new SolidColorBrush((Color)ColorConverter.ConvertFromString(h)); b.Freeze(); return b; }
        static readonly CultureInfo FR = new CultureInfo("fr-FR");

        readonly Config cfg;
        readonly Border card = new Border();
        readonly StackPanel root = new StackPanel();
        Model model; string error = "";
        readonly DispatcherTimer timer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(10) };
        WF.NotifyIcon tray;
        bool busy;

        public Widget(Config c, Model preview = null)
        {
            cfg = c;
            Title = "Cockpit PEA";
            WindowStyle = WindowStyle.None; AllowsTransparency = true; Background = Brushes.Transparent;
            ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false; SizeToContent = SizeToContent.Height;
            Topmost = cfg.Topmost;
            Width = cfg.Large ? 340 : 230;
            card.CornerRadius = new CornerRadius(18);
            card.Background = new SolidColorBrush(Color.FromArgb(0xEE, 0x14, 0x19, 0x22));
            card.BorderBrush = new SolidColorBrush(Color.FromArgb(0x30, 0xFF, 0xFF, 0xFF)); card.BorderThickness = new Thickness(1);
            card.Padding = new Thickness(16, 14, 16, 14);
            card.Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 18, ShadowDepth = 3, Opacity = .35 };
            card.Child = root; Content = new Border { Padding = new Thickness(10), Child = card };
            MouseLeftButtonDown += (s, e) => { if (e.ButtonState == MouseButtonState.Pressed && !Clickable(e.OriginalSource as DependencyObject)) try { DragMove(); } catch { } };
            LocationChanged += (s, e) => { cfg.Left = Left; cfg.Top = Top; };
            Deactivated += (s, e) => cfg.Save();
            ContextMenu = BuildMenu();
            if (!double.IsNaN(cfg.Left) && !double.IsNaN(cfg.Top) && OnScreen(cfg.Left, cfg.Top)) { Left = cfg.Left; Top = cfg.Top; }
            else { WindowStartupLocation = WindowStartupLocation.Manual; Left = SystemParameters.WorkArea.Right - Width - 24; Top = SystemParameters.WorkArea.Top + 24; }
            if (preview != null) { model = preview; Render(); return; }
            LoadCache();
            Render();
            timer.Tick += async (s, e) => await Refresh(false);
            timer.Start();
            Loaded += async (s, e) => await Refresh(false);
            SetupTray();
        }

        static bool Clickable(DependencyObject d)
        {
            while (d != null)
            {
                if (d is FrameworkElement fe && fe.Cursor == Cursors.Hand) return true;
                d = (d is Visual) ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d);
            }
            return false;
        }
        double CW => cfg.Large ? 286 : 176;

        static bool OnScreen(double l, double t) => l > SystemParameters.VirtualScreenLeft - 50 && t > SystemParameters.VirtualScreenTop - 50 &&
            l < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 80 && t < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 60;

        // ---------- menus
        ContextMenu BuildMenu()
        {
            var cm = new ContextMenu();
            cm.Opened += (s, e) =>
            {
                cm.Items.Clear();
                cm.Items.Add(Item("Actualiser", async () => await Refresh(true)));
                cm.Items.Add(Item("Ouvrir le Cockpit", () => Open("executif")));
                cm.Items.Add(new Separator());
                cm.Items.Add(Item(cfg.Large ? "Format petit" : "Format grand", ToggleSize));
                cm.Items.Add(Item((cfg.Topmost ? "✓ " : "") + "Toujours au premier plan", ToggleTop));
                cm.Items.Add(Item((Config.StartupEnabled ? "✓ " : "") + "Lancer au démarrage de Windows", () => Config.StartupEnabled = !Config.StartupEnabled));
                cm.Items.Add(Item("Masquer (icône près de l'horloge)", HideWidget));
                cm.Items.Add(new Separator());
                cm.Items.Add(Item("Paramètres…", ShowSettings));
                cm.Items.Add(Item("Quitter", Quit));
            };
            return cm;
        }
        static MenuItem Item(string t, Action a) { var i = new MenuItem { Header = t }; i.Click += (s, e) => a(); return i; }

        void SetupTray()
        {
            tray = new WF.NotifyIcon { Text = "Cockpit PEA", Visible = true };
            try { tray.Icon = System.Drawing.Icon.ExtractAssociatedIcon(Process.GetCurrentProcess().MainModule.FileName); } catch { tray.Icon = System.Drawing.SystemIcons.Application; }
            var m = new WF.ContextMenuStrip();
            m.Items.Add("Afficher / masquer", null, (s, e) => { if (IsVisible) HideWidget(); else ShowWidget(); });
            m.Items.Add("Actualiser", null, async (s, e) => await Refresh(true));
            m.Items.Add("Ouvrir le Cockpit", null, (s, e) => Open("executif"));
            m.Items.Add("Paramètres…", null, (s, e) => ShowSettings());
            m.Items.Add("Quitter", null, (s, e) => Quit());
            tray.ContextMenuStrip = m;
            tray.MouseClick += (s, e) => { if (e.Button == WF.MouseButtons.Left) { if (IsVisible) { Activate(); } else ShowWidget(); } };
        }
        void HideWidget() { cfg.Hidden = true; cfg.Save(); Hide(); try { tray.ShowBalloonTip(2500, "Cockpit PEA", "Le widget est caché. Clique sur l'icône près de l'horloge pour le réafficher.", WF.ToolTipIcon.None); } catch { } }
        void ShowWidget() { cfg.Hidden = false; cfg.Save(); Show(); Activate(); }
        void ToggleSize() { cfg.Large = !cfg.Large; Width = cfg.Large ? 340 : 230; cfg.Save(); Render(); }
        void ToggleTop() { cfg.Topmost = !cfg.Topmost; Topmost = cfg.Topmost; cfg.Save(); }
        void Quit() { cfg.Save(); try { tray.Visible = false; tray.Dispose(); } catch { } Application.Current.Shutdown(); }
        static void Open(string hash) { try { Process.Start(new ProcessStartInfo("https://tommy22032002.github.io/COCKPIT-/#" + hash) { UseShellExecute = true }); } catch { } }

        void ShowSettings()
        {
            var w = new Settings(cfg) { Owner = IsVisible ? this : null };
            if (w.ShowDialog() == true) { cfg.Save(); _ = Refresh(true); }
        }

        // ---------- cache (affichage immédiat au démarrage, même sans internet)
        void LoadCache()
        {
            try
            {
                if (!File.Exists(Config.CachePath)) return;
                var d = J.Parse(File.ReadAllText(Config.CachePath));
                model = Data.Build(J.Get(d, "snap"), J.Get(d, "raw"), J.Str(d, "brief"));
            }
            catch { }
        }
        void SaveCache(object snap, object raw, string brief)
        {
            try { File.WriteAllText(Config.CachePath, new JavaScriptSerializer { MaxJsonLength = int.MaxValue }.Serialize(new Dictionary<string, object> { ["snap"] = snap, ["raw"] = raw, ["brief"] = brief })); } catch { }
        }

        public async Task Refresh(bool manual)
        {
            if (busy) return; busy = true;
            if (manual) { error = "Actualisation…"; Render(); }
            try
            {
                var snapT = Data.LoadSync(cfg); var rawT = Data.LoadRaw(); var briefT = Data.LoadBriefTitle();
                var snap = await snapT; var raw = await rawT; var brief = await briefT;
                model = Data.Build(snap, raw, brief); error = "";
                SaveCache(snap, raw, brief);
            }
            catch (Exception ex) { error = ex.Message; }
            busy = false; Render();
        }

        // ---------- affichage
        static string Eur(double v, int dec = 0) => v.ToString(dec == 0 ? "#,##0" : "#,##0.00", FR).Replace(' ', ' ') + " €";
        static string SEur(double v) => (v > 0 ? "+" : v < 0 ? "−" : "") + Eur(Math.Abs(v), 2);
        static string SPct(double v) => (v > 0 ? "+" : v < 0 ? "−" : "") + (Math.Abs(v) * 100).ToString("0.00", FR) + " %";
        static TextBlock T(string s, double size, Brush c, FontWeight? w = null, Thickness? m = null)
            => new TextBlock { Text = s, FontSize = size, Foreground = c, FontWeight = w ?? FontWeights.Normal, Margin = m ?? new Thickness(0), FontFamily = new FontFamily("Segoe UI Variable Text, Segoe UI"), TextWrapping = TextWrapping.Wrap };

        void Render()
        {
            root.Children.Clear();
            var head = new DockPanel { LastChildFill = true };
            var menu = T("⋯", 16, Muted); menu.Cursor = Cursors.Hand; menu.Margin = new Thickness(8, -4, 0, 0);
            menu.MouseLeftButtonUp += (s, e) => { ContextMenu.PlacementTarget = this; ContextMenu.IsOpen = true; };
            DockPanel.SetDock(menu, Dock.Right); head.Children.Add(menu);
            var rf = T("↻", 14, Muted); rf.Cursor = Cursors.Hand; rf.Margin = new Thickness(8, -1, 0, 0); rf.ToolTip = "Actualiser";
            rf.MouseLeftButtonUp += async (s, e) => await Refresh(true);
            DockPanel.SetDock(rf, Dock.Right); head.Children.Add(rf);
            head.Children.Add(T("COCKPIT · PEA", 11, Muted, FontWeights.SemiBold));
            root.Children.Add(head);

            if (model == null)
            {
                root.Children.Add(T(string.IsNullOrEmpty(error) ? "Chargement…" : error, 13, Ink, null, new Thickness(0, 10, 0, 4)));
                if (string.IsNullOrEmpty(cfg.Token)) { var b = T("Configurer le widget →", 13, Accent, FontWeights.SemiBold, new Thickness(0, 6, 0, 0)); b.Cursor = Cursors.Hand; b.MouseLeftButtonUp += (s, e) => ShowSettings(); root.Children.Add(b); }
                return;
            }
            var m = model; bool p = m.Privacy;
            var val = T(p ? "•••• €" : Eur(m.Value), cfg.Large ? 30 : 24, Ink, FontWeights.SemiBold, new Thickness(0, 2, 0, 0));
            val.Cursor = Cursors.Hand; val.ToolTip = "Ouvrir le portefeuille"; val.MouseLeftButtonUp += (s, e) => Open("portefeuille");
            root.Children.Add(val);
            root.Children.Add(T((p ? "••" : SEur(m.DayAbs)) + "   " + SPct(m.DayPct) + "  aujourd'hui", 13, m.DayAbs >= 0 ? Gain : Loss));
            var sub = new DockPanel { Margin = new Thickness(0, 2, 0, 0) };
            var at = T("màj " + m.PriceAt.ToString("HH:mm", FR), 10.5, Faint); DockPanel.SetDock(at, Dock.Right); sub.Children.Add(at);
            sub.Children.Add(T("Gain total " + (p ? "••" : SEur(m.Gain)), 11.5, Muted));
            root.Children.Add(sub);
            if (!string.IsNullOrEmpty(error)) root.Children.Add(T(error, 11, SoftLoss, null, new Thickness(0, 4, 0, 0)));
            if (!cfg.Large) return;

            // période + courbe
            var chips = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 4) };
            for (int i = 0; i < 4; i++)
            {
                int idx = i;
                var b = new Border { CornerRadius = new CornerRadius(9), Padding = new Thickness(9, 3, 9, 3), Margin = new Thickness(0, 0, 4, 0), Cursor = Cursors.Hand,
                    Background = i == cfg.Period ? new SolidColorBrush(Color.FromArgb(0x40, 0x6E, 0xA8, 0xFE)) : Brushes.Transparent,
                    Child = T(Periods[i], 11.5, i == cfg.Period ? Ink : Muted) };
                b.MouseLeftButtonUp += (s, e) => { cfg.Period = idx; cfg.Save(); Render(); };
                chips.Children.Add(b);
            }
            root.Children.Add(chips);
            root.Children.Add(Spark(m, CW, 92, out double delta, out double dpct));
            root.Children.Add(T((p ? "••" : SEur(delta)) + "  " + SPct(dpct) + (cfg.Period == 3 ? " depuis le début" : " sur " + Periods[cfg.Period]) + "   ─ valeur  ┄ versé", 11, Muted, null, new Thickness(0, 2, 0, 0)));

            // répartition
            var row = new DockPanel { Margin = new Thickness(0, 12, 0, 0) };
            var donut = Donut(m, 86); DockPanel.SetDock(donut, Dock.Left); row.Children.Add(donut);
            var leg = new StackPanel { Margin = new Thickness(12, 2, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            double tot = m.Lines.Sum(l => l.Q * l.Px) + Math.Max(0, m.Cash);
            int ci = 0;
            foreach (var l in m.Lines.Where(x => x.Q * x.Px > 0)) leg.Children.Add(LegRow(Pal[ci++ % Pal.Length], l.N, tot > 0 ? l.Q * l.Px / tot : 0));
            if (m.Cash > 0.5) leg.Children.Add(LegRow("#5A6474", "Espèces", tot > 0 ? m.Cash / tot : 0));
            row.Children.Add(leg);
            row.Cursor = Cursors.Hand; row.MouseLeftButtonUp += (s, e) => Open("allocation");
            root.Children.Add(row);

            // objectifs
            root.Children.Add(BarRow("Apport immobilier", (p ? "••" : Eur(m.ApCur)) + " / " + Eur(m.ApTgt), m.ApCur / m.ApTgt, "plan"));
            root.Children.Add(BarRow("Objectif " + Eur(m.MsTgt), (Math.Min(1, m.MsCur / m.MsTgt) * 100).ToString("0", FR) + " %", m.MsCur / m.MsTgt, "plan"));
            if (!string.IsNullOrEmpty(m.Next)) { var n = T(m.Next, 12, Accent, FontWeights.SemiBold, new Thickness(0, 10, 0, 0)); n.Cursor = Cursors.Hand; n.MouseLeftButtonUp += (s, e) => Open("achat"); root.Children.Add(n); }
            if (!string.IsNullOrEmpty(m.Brief))
            {
                var bp = new StackPanel { Margin = new Thickness(0, 10, 0, 0), Cursor = Cursors.Hand };
                bp.Children.Add(T("LE POINT DU JOUR", 9.5, Faint, FontWeights.SemiBold));
                bp.Children.Add(T(m.Brief, 12, Ink, null, new Thickness(0, 2, 0, 0)));
                bp.MouseLeftButtonUp += (s, e) => Open("journal");
                root.Children.Add(bp);
            }
            if (!string.IsNullOrEmpty(m.From)) root.Children.Add(T("Données de " + m.From + " · " + m.SyncAt.ToLocalTime().ToString("dd/MM HH:mm", FR), 9.5, Faint, null, new Thickness(0, 8, 0, 0)));
        }

        static FrameworkElement LegRow(string color, string name, double share)
        {
            var d = new DockPanel { Margin = new Thickness(0, 1, 0, 1) };
            var pct = T((share * 100).ToString("0", FR) + " %", 11.5, Ink); DockPanel.SetDock(pct, Dock.Right); d.Children.Add(pct);
            var dot = new Ellipse { Width = 8, Height = 8, Fill = B(color), Margin = new Thickness(0, 0, 7, 0), VerticalAlignment = VerticalAlignment.Center };
            DockPanel.SetDock(dot, Dock.Left); d.Children.Add(dot);
            var n = T(name, 11.5, Muted); n.TextWrapping = TextWrapping.NoWrap; n.TextTrimming = TextTrimming.CharacterEllipsis; d.Children.Add(n);
            return d;
        }

        FrameworkElement BarRow(string label, string right, double ratio, string hash)
        {
            var sp = new StackPanel { Margin = new Thickness(0, 9, 0, 0), Cursor = Cursors.Hand };
            var d = new DockPanel();
            var r = T(right, 11.5, Ink); DockPanel.SetDock(r, Dock.Right); d.Children.Add(r);
            d.Children.Add(T(label, 11.5, Muted));
            sp.Children.Add(d);
            var g = new Grid { Height = 6, Margin = new Thickness(0, 4, 0, 0) };
            g.Children.Add(new Border { Background = Track, CornerRadius = new CornerRadius(3) });
            g.Children.Add(new Border { Background = Accent, CornerRadius = new CornerRadius(3), HorizontalAlignment = HorizontalAlignment.Left, Width = Math.Max(0, Math.Min(1, ratio)) * CW });
            sp.Children.Add(g);
            sp.MouseLeftButtonUp += (s, e) => Open(hash);
            return sp;
        }

        FrameworkElement Spark(Model m, double w, double h, out double delta, out double dpct)
        {
            var c = new Canvas { Width = w, Height = h, ClipToBounds = false, Cursor = Cursors.Hand };
            c.MouseLeftButtonUp += (s, e) => Open("performance");
            delta = 0; dpct = 0;
            var from = DateTime.Today.AddDays(-PeriodDays[cfg.Period]);
            var pts = m.Hist.Where(t => t.Item1 >= from).ToList();
            var before = m.Hist.LastOrDefault(t => t.Item1 < from);
            if (before != null && cfg.Period < 3) pts.Insert(0, Tuple.Create(from, before.Item2, before.Item3));
            if (pts.Count < 2) { c.Children.Add(T("Pas encore assez d'historique", 11, Muted)); return c; }
            double lo = pts.Min(t => Math.Min(t.Item2, t.Item3)), hi = pts.Max(t => Math.Max(t.Item2, t.Item3));
            double pad = Math.Max((hi - lo) * .08, hi * .002 + 1); lo = Math.Max(0, lo - pad); hi += pad;
            double t0 = pts[0].Item1.Ticks, t1 = Math.Max(t0 + 1, pts[pts.Count - 1].Item1.Ticks);
            Func<DateTime, double> X = d => (d.Ticks - t0) / (t1 - t0) * w;
            Func<double, double> Y = v => h - (v - lo) / (hi - lo) * h;
            var line = new Polyline { Stroke = Accent, StrokeThickness = 2, StrokeLineJoin = PenLineJoin.Round };
            var verse = new Polyline { Stroke = new SolidColorBrush(Color.FromArgb(0xA0, 0x8B, 0x95, 0xA7)), StrokeThickness = 1.2, StrokeDashArray = new DoubleCollection { 4, 3 } };
            var area = new Polygon { Fill = new LinearGradientBrush(Color.FromArgb(0x55, 0x6E, 0xA8, 0xFE), Color.FromArgb(0x00, 0x6E, 0xA8, 0xFE), 90) };
            area.Points.Add(new Point(X(pts[0].Item1), h));
            for (int i = 0; i < pts.Count; i++)
            {
                var pt = new Point(X(pts[i].Item1), Y(pts[i].Item2));
                line.Points.Add(pt); area.Points.Add(pt);
                if (i > 0) verse.Points.Add(new Point(pt.X, Y(pts[i - 1].Item3)));
                verse.Points.Add(new Point(pt.X, Y(pts[i].Item3)));
            }
            area.Points.Add(new Point(X(pts[pts.Count - 1].Item1), h));
            c.Children.Add(area); c.Children.Add(verse); c.Children.Add(line);
            var last = line.Points[line.Points.Count - 1];
            var dot = new Ellipse { Width = 7, Height = 7, Fill = Accent }; Canvas.SetLeft(dot, last.X - 3.5); Canvas.SetTop(dot, last.Y - 3.5); c.Children.Add(dot);
            double first = pts[0].Item2, lastV = pts[pts.Count - 1].Item2, flows = pts[pts.Count - 1].Item3 - pts[0].Item3;
            delta = lastV - first - flows; double baseV = first + Math.Max(0, flows); dpct = baseV > 0 ? delta / baseV : 0;
            return c;
        }

        FrameworkElement Donut(Model m, double size)
        {
            var c = new Canvas { Width = size, Height = size };
            var vals = m.Lines.Where(l => l.Q * l.Px > 0).Select(l => l.Q * l.Px).ToList();
            if (m.Cash > 0.5) vals.Add(m.Cash);
            double tot = vals.Sum(); if (tot <= 0) return c;
            double r = size / 2 - 8, cx = size / 2, cy = size / 2, a = -Math.PI / 2;
            for (int i = 0; i < vals.Count; i++)
            {
                double sweep = vals[i] / tot * 2 * Math.PI;
                bool cash = m.Cash > 0.5 && i == vals.Count - 1;
                double a0 = a + .03, a1 = a + Math.Max(.05, sweep - .03);
                var fig = new PathFigure { StartPoint = new Point(cx + r * Math.Cos(a0), cy + r * Math.Sin(a0)) };
                fig.Segments.Add(new ArcSegment(new Point(cx + r * Math.Cos(a1), cy + r * Math.Sin(a1)), new Size(r, r), 0, a1 - a0 > Math.PI, SweepDirection.Clockwise, true));
                var geo = new PathGeometry(); geo.Figures.Add(fig);
                c.Children.Add(new System.Windows.Shapes.Path { Data = geo, Stroke = B(cash ? "#5A6474" : Pal[i % Pal.Length]), StrokeThickness = 14 });
                a += sweep;
            }
            var n = T(m.Lines.Count(l => l.Q * l.Px > 0).ToString(), 18, Ink, FontWeights.SemiBold);
            n.Width = size; n.TextAlignment = TextAlignment.Center; Canvas.SetTop(n, size / 2 - 17); c.Children.Add(n);
            var e2 = T("ETF", 9.5, Muted); e2.Width = size; e2.TextAlignment = TextAlignment.Center; Canvas.SetTop(e2, size / 2 + 5); c.Children.Add(e2);
            return c;
        }
    }

    // ------------------------------------------------------------------ paramètres
    class Settings : Window
    {
        readonly Config cfg; readonly TextBox tok = new TextBox(), code = new TextBox(); readonly CheckBox start = new CheckBox(); readonly TextBlock msg = new TextBlock();
        public Settings(Config c)
        {
            cfg = c; Title = "Cockpit PEA · widget"; Width = 460; SizeToContent = SizeToContent.Height; ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen; FontFamily = new FontFamily("Segoe UI"); FontSize = 13.5;
            var sp = new StackPanel { Margin = new Thickness(20) };
            sp.Children.Add(new TextBlock { Text = "Widget de ton Cockpit PEA", FontSize = 18, FontWeight = FontWeights.SemiBold });
            sp.Children.Add(new TextBlock { Text = "Le widget lit tes données via la synchro du Cockpit (activée sur ton téléphone ou ton PC). Mets la même clé GitHub et le même code que dans le Cockpit (Mettre à jour → Synchro). Ils restent chiffrés sur ce PC.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 6, 0, 14), Foreground = Brushes.DimGray });
            sp.Children.Add(new TextBlock { Text = "Clé d'accès GitHub (ghp_…)" });
            tok.Text = c.Token; tok.Margin = new Thickness(0, 4, 0, 12); tok.Padding = new Thickness(6); tok.FontFamily = new FontFamily("Consolas"); sp.Children.Add(tok);
            sp.Children.Add(new TextBlock { Text = "Code de synchro" });
            code.Text = c.Code; code.Margin = new Thickness(0, 4, 0, 12); code.Padding = new Thickness(6); code.FontFamily = new FontFamily("Consolas"); sp.Children.Add(code);
            start.Content = "Lancer le widget au démarrage de Windows"; start.IsChecked = string.IsNullOrEmpty(c.Token) || Config.StartupEnabled; sp.Children.Add(start);
            msg.TextWrapping = TextWrapping.Wrap; msg.Margin = new Thickness(0, 10, 0, 0); msg.Foreground = Brushes.Firebrick; sp.Children.Add(msg);
            var bt = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
            var ok = new Button { Content = "Enregistrer", Padding = new Thickness(16, 6, 16, 6), IsDefault = true };
            var cancel = new Button { Content = "Annuler", Padding = new Thickness(16, 6, 16, 6), Margin = new Thickness(8, 0, 0, 0), IsCancel = true };
            ok.Click += async (s, e) =>
            {
                var t = tok.Text.Trim(); var k = code.Text.Trim();
                if (!(t.StartsWith("ghp_") || t.StartsWith("github_pat_"))) { msg.Text = "La clé doit commencer par ghp_."; return; }
                if (k.Length < 6) { msg.Text = "Le code doit faire au moins 6 caractères."; return; }
                ok.IsEnabled = false; msg.Foreground = Brushes.DimGray; msg.Text = "Vérification…";
                var test = new Config { Token = t, Code = k };
                try { await Data.LoadSync(test); }
                catch (Exception ex) { msg.Foreground = Brushes.Firebrick; msg.Text = ex.Message; ok.IsEnabled = true; return; }
                cfg.Token = t; cfg.Code = k; Config.StartupEnabled = start.IsChecked == true; DialogResult = true;
            };
            bt.Children.Add(ok); bt.Children.Add(cancel); sp.Children.Add(bt);
            Content = sp;
        }
    }

    // ------------------------------------------------------------------ démarrage
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length >= 3 && args[0] == "--selftest") return SelfTest(args[1], args[2]);
            if (args.Length >= 3 && args[0] == "--render") return RenderPreview(args[1], args[2]);
            bool created;
            using (var mutex = new Mutex(true, "CockpitPEA.Widget", out created))
            {
                if (!created) return 0;
                var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                var cfg = Config.Load();
                if (string.IsNullOrEmpty(cfg.Token))
                {
                    var s = new Settings(cfg);
                    if (s.ShowDialog() != true) return 0;
                    cfg.Save();
                }
                var w = new Widget(cfg);
                if (!cfg.Hidden) w.Show();
                app.Run();
            }
            return 0;
        }

        // Capture du widget (petit et grand) pour vérifier le rendu pendant la compilation.
        static int RenderPreview(string input, string outDir)
        {
            try
            {
                var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                var v = J.Parse(File.ReadAllText(input));
                var plain = Crypto.Decrypt(J.Str(v, "salt"), J.Str(v, "iv"), J.Str(v, "ct"), J.Str(v, "code"));
                var snap = J.Parse(plain) as Dictionary<string, object>; snap["__dev"] = "Appli Android"; snap["__at"] = DateTime.UtcNow.ToString("o");
                var model = Data.Build(snap, J.Get(v, "raw"), "Les marchés montent avant une semaine chargée en banques centrales");
                Directory.CreateDirectory(outDir);
                foreach (var large in new[] { true, false })
                    foreach (var per in new[] { 1, 3 })
                    {
                        if (!large && per == 3) continue;
                        var w = new Widget(new Config { Large = large, Period = per }, model) { Left = -5000, Top = -5000, ShowActivated = false };
                        w.Show(); w.UpdateLayout();
                        var el = (FrameworkElement)w.Content;
                        var rtb = new System.Windows.Media.Imaging.RenderTargetBitmap((int)Math.Ceiling(el.ActualWidth * 2), (int)Math.Ceiling(el.ActualHeight * 2), 192, 192, PixelFormats.Pbgra32);
                        rtb.Render(el);
                        var enc = new System.Windows.Media.Imaging.PngBitmapEncoder(); enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(rtb));
                        using (var fs = File.Create(System.IO.Path.Combine(outDir, (large ? "grand" : "petit") + "-" + per + ".png"))) enc.Save(fs);
                        w.Close();
                    }
                return 0;
            }
            catch (Exception ex) { File.WriteAllText(System.IO.Path.Combine(outDir, "erreur.txt"), ex.ToString()); return 1; }
        }

        // Autotest utilisé par la compilation : déchiffre un paquet produit par le Cockpit et calcule le modèle.
        static int SelfTest(string input, string output)
        {
            var sb = new StringBuilder();
            int rc = 0;
            try
            {
                var v = J.Parse(File.ReadAllText(input));
                sb.AppendLine("norm=" + Crypto.Norm(J.Str(v, "code")));
                var plain = Crypto.Decrypt(J.Str(v, "salt"), J.Str(v, "iv"), J.Str(v, "ct"), J.Str(v, "code"));
                sb.AppendLine("decrypt=OK len=" + plain.Length);
                try { Crypto.Decrypt(J.Str(v, "salt"), J.Str(v, "iv"), J.Str(v, "ct"), "mauvais-code"); sb.AppendLine("wrongcode=ACCEPTED (BUG)"); rc = 2; }
                catch (CryptographicException) { sb.AppendLine("wrongcode=rejected"); }
                var snap = J.Parse(plain);
                var m = Data.Build(snap, J.Get(v, "raw"), "Titre test");
                sb.AppendLine(string.Format(CultureInfo.InvariantCulture, "value={0:F2} verse={1:F2} day={2:F2} lines={3} hist={4} next={5}", m.Value, m.Verse, m.DayAbs, m.Lines.Count, m.Hist.Count, m.Next));
                if (Math.Abs(m.Value - J.Num(v, "expectValue")) > 0.05) { sb.AppendLine("VALUE MISMATCH expected " + J.Num(v, "expectValue")); rc = 3; }
            }
            catch (Exception ex) { sb.AppendLine("ERROR " + ex); rc = 1; }
            File.WriteAllText(output, sb.ToString());
            return rc;
        }
    }
}
