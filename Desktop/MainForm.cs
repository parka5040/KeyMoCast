using WindowsInput;
using WindowsInput.Native;
using Fleck;
using Newtonsoft.Json;

namespace RemoteControlServer
{
    public partial class MainForm : Form
    {
        private readonly InputSimulator inputSimulator;
        private WebSocketServer? server;
        private IWebSocketConnection? currentConnection;
        private string currentPin = string.Empty;
        private readonly Dictionary<string, Action<dynamic>> messageHandlers;
        private Label statusLabel = new();
        private TableLayoutPanel mainTableLayout = new();
        private float currentDpiScale;
        private const int PIN_LENGTH = 4;

        private DateTime lastKeepAliveReceived = DateTime.Now;
        private DateTime lastMessageSent = DateTime.Now;
        private System.Threading.Timer? keepAliveMonitorTimer;
        private System.Threading.Timer? periodicPingTimer;
        private const int KEEP_ALIVE_CHECK_INTERVAL = 5000;  // 5 seconds
        private const int KEEP_ALIVE_TIMEOUT = 45000;        // 45 seconds
        private const int PING_INTERVAL = 30000;             // 30 seconds
        private bool isClosing = false;

        private readonly string instructionList = String.Join(
                      Environment.NewLine + Environment.NewLine,
                [
                "Instructions:",
                  "1. Make sure your phone and computer are on the same network",
                  "2. Start the Android app",
                  "3. Enter the PIN",
                  "4. Wait for connection on the Android end."
                ]);
        public MainForm()
        {
            InitializeComponent();
            inputSimulator = new InputSimulator();
            messageHandlers = InitializeMessageHandlers();
            currentPin = GeneratePin();
            currentDpiScale = DeviceDpi / 96f;

            this.AutoScaleDimensions = new SizeF(96F, 96F);
            this.AutoScaleMode = AutoScaleMode.Dpi;
            this.MinimumSize = new Size(ScaleWidth(400), ScaleHeight(450));

            SetupLayout();
            InitializeControls();
            StartServer();

            // This kinda works
            this.ResizeEnd += (s, e) => this.PerformLayout();
        }

        private void SetupLayout()
        {
            mainTableLayout = new TableLayoutPanel
            {

                Dock = DockStyle.Fill,

                AutoSize = true,

                AutoSizeMode = AutoSizeMode.GrowAndShrink,

                Padding = new Padding(ScaleWidth(20)),

                ColumnCount = 3,

                RowCount = 6

            };
            mainTableLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40F));
            mainTableLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 30F));
            mainTableLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 30F));

            for (int i = 0; i < 6; i++)
            {
                mainTableLayout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            }
            this.Controls.Add(mainTableLayout);
        }

        private int ScaleWidth(int width) => (int)(width * currentDpiScale);
        private int ScaleHeight(int height) => (int)(height * currentDpiScale);
        private Font ScaleFont(string familyName, float size,
                               FontStyle style = FontStyle.Regular)

            => new Font(familyName, size * currentDpiScale, style);

        private void InitializeControls()
        {
            var ipAddressLabel = new Label
            {

                Text = $"IP Address: {AppSettings.GetLocalIPAddress()}",

                Font = ScaleFont("Segoe UI", 9f),

                AutoSize = true,

                Dock = DockStyle.Fill
            };
            mainTableLayout.Controls.Add(ipAddressLabel, 0, 0);
            mainTableLayout.SetColumnSpan(ipAddressLabel, 3);

            var portLabel = new Label
            {
                Text = "Port:",
                Font = ScaleFont("Segoe UI", 9f),
                AutoSize = true,
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };

            mainTableLayout.Controls.Add(portLabel, 0, 1);
            var portInput = new NumericUpDown
            {
                Minimum = 1024,
                Maximum = 65535,
                Value = AppSettings.Port,
                Font = ScaleFont("Segoe UI", 9f),
                Width = ScaleWidth(80),
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };

            mainTableLayout.Controls.Add(portInput, 1, 1);
            var applyButton = new Button
            {
                Text = "Apply",
                Font = ScaleFont("Segoe UI", 9f),
                Width = ScaleWidth(120),
                Height = ScaleHeight(23),

                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };

            applyButton.Click += (s, e) =>
            {
                AppSettings.Port = (int)portInput.Value;
                RestartServer();
            };

            mainTableLayout.Controls.Add(applyButton, 2, 1);

            var pinLabel = new Label
            {
                Text = "Connection PIN:",
                Font = ScaleFont("Segoe UI", 9f),
                AutoSize = true,
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };

            mainTableLayout.Controls.Add(pinLabel, 0, 2);

            var pinDisplay = new TextBox
            {
                Text = currentPin,
                Font = ScaleFont("Consolas", 16f),
                ReadOnly = true,
                TextAlign = HorizontalAlignment.Center,
                Width = ScaleWidth(100),
                Height = ScaleHeight(30),
                BorderStyle = BorderStyle.FixedSingle,
                Anchor = AnchorStyles.Left | AnchorStyles.Top

            };

            mainTableLayout.Controls.Add(pinDisplay, 1, 2);
            var regenerateButton = new Button
            {
                Text = "Regenerate PIN",
                Font = ScaleFont("Segoe UI", 9f),
                Width = ScaleWidth(120),
                Height = ScaleHeight(30),
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };

            regenerateButton.Click += (s, e) =>
            {
                currentPin = GeneratePin();
                pinDisplay.Text = currentPin;
            };

            mainTableLayout.Controls.Add(regenerateButton, 2, 2);
            statusLabel = new Label
            {
                Text = "Status: Waiting for connection",
                Font = ScaleFont("Segoe UI", 9f),
                AutoSize = true,
                Anchor = AnchorStyles.Left | AnchorStyles.Top
            };
            mainTableLayout.Controls.Add(statusLabel, 0, 3);
            mainTableLayout.SetColumnSpan(statusLabel, 3);

            var instructions = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                BackColor = SystemColors.Control,
                BorderStyle = BorderStyle.None,
                Font = ScaleFont("Segoe UI", 9f),
                Dock = DockStyle.Fill,
                WordWrap = true,
                Text = instructionList
            };
            mainTableLayout.Controls.Add(instructions, 0, 4);
            mainTableLayout.SetColumnSpan(instructions, 3);
            mainTableLayout.SetRowSpan(instructions, 2);
        }

        protected override void OnDpiChanged(DpiChangedEventArgs e)
        {
            base.OnDpiChanged(e);
            float newDpiScale = e.DeviceDpiNew / 96f;
            currentDpiScale = newDpiScale;
            this.MinimumSize = new Size(ScaleWidth(400), ScaleHeight(450));
            this.Controls.Clear();

            SetupLayout();
            InitializeControls();

            this.PerformLayout();
        }

        private Dictionary<string, Action<dynamic>> InitializeMessageHandlers()
        {
            return new Dictionary<string, Action<dynamic>>
            {
                ["mouseMove"] = HandleMouseMove,
                ["mouseClick"] = HandleMouseClick,
                ["scroll"] = HandleScroll,
                ["keyInput"] = HandleKeyInput,
                ["authenticate"] = HandleAuthentication,
                ["keepAlive"] = HandleKeepAlive,
                ["ping"] = HandlePing
            };
        }

        private void HandleKeepAlive(dynamic data)
        {
            if (currentConnection == null)
                return;

            var response = new
            {
                type = "ack",
                receivedType = "keepAlive",
                timestamp = data.timestamp
            };

            currentConnection.Send(JsonConvert.SerializeObject(response));
            lastMessageSent = DateTime.Now;
            lastKeepAliveReceived = DateTime.Now;
        }

        private void HandlePing(dynamic data)
        {
            if (currentConnection == null)
                return;

            var response = new
            {
                type = "pong",
                timestamp = data.timestamp
            };

            currentConnection.Send(JsonConvert.SerializeObject(response));
            lastMessageSent = DateTime.Now;
            lastKeepAliveReceived = DateTime.Now;
        }

        private void UpdateStatus(string status)
        {
            if (!IsDisposed)
            {
                try
                {
                    if (InvokeRequired)
                    {
                        Invoke(new Action(() => UpdateStatus(status)));
                        return;
                    }
                    statusLabel.Text = $"Status: {status}";
                }

                catch (Exception ex)
                {
                    Console.WriteLine($"Unexpected error updating status: {ex.Message}");
                }
            }
        }

        private void StartServer()
        {
            try
            {
                var localAddresses = AppSettings.GetAllLocalIPAddresses();
                if (localAddresses.Count == 0)
                {
                    MessageBox.Show("No valid IPs found! Check your internet connection.",
                                    "Error",
                                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return;
                }

                int port = AppSettings.Port;
                StopServer();
                isClosing = false;

                foreach (var address in localAddresses)
                {
                    var wsServer = new WebSocketServer($"ws://{address}:{port}");
                    wsServer.RestartAfterListenError = true;

                    wsServer.Start(socket =>
                    {
                        socket.OnOpen = () =>
                        {
                            if (currentConnection != null)
                            {
                                socket.Close();
                                return;
                            }
                            currentConnection = socket;
                            lastKeepAliveReceived = DateTime.Now;
                            lastMessageSent = DateTime.Now;
                            StartConnectionMonitoring();
                            UpdateStatus($"Phone connected from {socket.ConnectionInfo.ClientIpAddress}, waiting for authentication");
                        };

                        socket.OnClose = () =>
                        {
                            if (currentConnection == socket)
                            {
                                HandleDisconnection();
                            }
                        };

                        socket.OnMessage = message =>
                        {
                            if (message != null)
                            {
                                HandleMessage(socket, message);
                                lastKeepAliveReceived = DateTime.Now;
                            }
                        };
                    });
                }

                UpdateStatus($"Server running on port {port}.");
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to start server: {ex.Message}", "Error",
                                MessageBoxButtons.OK, MessageBoxIcon.Error);
                UpdateStatus("Server failed to start");
            }
        }

        private void StopServer()
        {
            isClosing = true;
            StopConnectionMonitoring();
            if (currentConnection != null)
            {
                try
                {
                    currentConnection.Close();
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error closing connection: {ex.Message}");
                }
                currentConnection = null;
            }
            if (server != null)
            {
                try
                {
                    server.Dispose();
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error disposing server: {ex.Message}");
                }
                server = null;
            }
        }

        private void StartConnectionMonitoring()
        {
            StopConnectionMonitoring();

            keepAliveMonitorTimer = new System.Threading.Timer(
                CheckConnectionStatus,
                null,
                KEEP_ALIVE_CHECK_INTERVAL,
                KEEP_ALIVE_CHECK_INTERVAL);

            periodicPingTimer = new System.Threading.Timer(
                SendPeriodicPing,
                null,
                PING_INTERVAL,
                PING_INTERVAL);
        }

        private void StopConnectionMonitoring()
        {
            if (keepAliveMonitorTimer != null)
            {
                keepAliveMonitorTimer.Dispose();
                keepAliveMonitorTimer = null;
            }
            if (periodicPingTimer != null)
            {
                periodicPingTimer.Dispose();
                periodicPingTimer = null;
            }
        }

        private void CheckConnectionStatus(object? state)
        {
            if (isClosing) return;

            try
            {
                if (currentConnection != null)
                {
                    var timeSinceLastKeepAlive = DateTime.Now - lastKeepAliveReceived;
                    if (timeSinceLastKeepAlive.TotalMilliseconds > KEEP_ALIVE_TIMEOUT)
                    {
                        if (InvokeRequired)
                        {
                            Invoke(new Action(() =>
                            {
                                HandleDisconnection("Connection timeout");
                            }));
                        }
                        else
                        {
                            HandleDisconnection("Connection timeout");
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error in connection monitor: {ex.Message}");
            }
        }

        private void SendPeriodicPing(object? state)
        {
            if (isClosing) return;

            try
            {
                if (currentConnection != null)
                {
                    var timeSinceLastMessage = DateTime.Now - lastMessageSent;
                    if (timeSinceLastMessage.TotalMilliseconds > PING_INTERVAL)
                    {
                        if (InvokeRequired)
                        {
                            Invoke(new Action(() =>
                            {
                                SendPing();
                            }));
                        }
                        else
                        {
                            SendPing();
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error sending ping: {ex.Message}");
            }
        }

        private void SendPing()
        {
            if (currentConnection == null || isClosing) return;

            try
            {
                var message = new
                {
                    type = "ping",
                    timestamp = DateTimeOffset.Now.ToUnixTimeMilliseconds()
                };
                currentConnection.Send(JsonConvert.SerializeObject(message));
                lastMessageSent = DateTime.Now;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error sending ping: {ex.Message}");
                HandleDisconnection("Failed to send ping");
            }
        }

        private void HandleDisconnection(string reason = "Connection closed")
        {
            if (currentConnection != null)
            {
                try
                {
                    currentConnection.Close();
                }
                catch { }
                currentConnection = null;
            }
            StopConnectionMonitoring();
            UpdateStatus($"Phone disconnected: {reason}");
        }

        private void RestartServer()
        {
            UpdateStatus("Restarting server...");
            if (currentConnection != null)
            {
                currentConnection.Close();
                currentConnection = null;
            }

            StartServer();

            foreach (Control control in Controls)
            {
                if (control is TextBox textBox && textBox.Name == "instructions")
                {
                    textBox.Text = instructionList;
                    break;
                }
            }
        }

        private static string GeneratePin()
        {
            byte[] pinBytes = new byte[PIN_LENGTH];
            System.Security.Cryptography.RandomNumberGenerator.Fill(pinBytes);
            return Math.Abs(BitConverter.ToInt32(pinBytes, 0) % 10000).ToString("D4");
        }

        private void HandleMessage(IWebSocketConnection socket, string message)
        {
            try
            {
                if (socket != currentConnection)
                {
                    UpdateStatus("Received message from unauthorized connection");
                    socket.Close();
                    return;
                }

                dynamic? data = JsonConvert.DeserializeObject(message);
                if (data == null)
                {
                    SendError(socket, "Invalid message format");
                    return;
                }

                string? messageType = data.type?.ToString();
                if (string.IsNullOrEmpty(messageType))
                {
                    SendError(socket, "Message type not specified");
                    return;
                }

                // Update last activity time for any valid message
                lastKeepAliveReceived = DateTime.Now;

                if (messageHandlers.TryGetValue(messageType, out var handler))
                {
                    if (messageType != "authenticate" && socket != currentConnection)
                    {
                        SendError(socket, "Not authenticated");
                        socket.Close();
                        return;
                    }
                    handler(data);

                    // Send acknowledgment
                    var response = new { type = "ack", receivedType = messageType };
                    socket.Send(JsonConvert.SerializeObject(response));
                    lastMessageSent = DateTime.Now;
                }
                else
                {
                    SendError(socket, $"Unknown message type: {messageType}");
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error handling message: {ex.Message}");
                SendError(socket, "Internal server error");
            }
        }

        private void SendError(IWebSocketConnection socket, string message)
        {
            try
            {
                var error = new { type = "error", message = message };
                socket.Send(JsonConvert.SerializeObject(error));
                lastMessageSent = DateTime.Now;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error sending error message: {ex.Message}");
            }
        }

        private void HandleAuthentication(dynamic data)
        {
            if (currentConnection == null)
                return;

            string? receivedPin = data.pin?.ToString();

            if (string.IsNullOrEmpty(receivedPin))
            {
                currentConnection.Close();
                UpdateStatus("Authentication failed: Invalid PIN format");
                return;
            }

            bool success = receivedPin == currentPin;

            var response = new
            {
                type = "authResponse",
                success
            };

            currentConnection.Send(JsonConvert.SerializeObject(response));

            if (!success)
            {
                currentConnection.Close();
                UpdateStatus("Authentication failed: Incorrect PIN");
            }

            else
                UpdateStatus("Client authenticated successfully");
        }

        private void HandleMouseMove(dynamic data)
        {
            float deltaX = (float)data.deltaX;
            float deltaY = (float)data.deltaY;
            float sensitivity = (float)data.sensitivity;

            inputSimulator.Mouse.MoveMouseBy(
                (int)(deltaX * sensitivity),
                (int)(deltaY * sensitivity)
            );
        }

        private void HandleMouseClick(dynamic data)
        {
            string button = data.button.ToString();
            bool isDouble = data.isDouble ?? false;
            switch (button.ToLower())
            {
                case "left":
                    if (isDouble)
                        inputSimulator.Mouse.LeftButtonDoubleClick();
                    else
                        inputSimulator.Mouse.LeftButtonClick();
                    break;

                case "right":
                    inputSimulator.Mouse.RightButtonClick();
                    break;
            }
        }

        private void HandleScroll(dynamic data)
        {
            int deltaY = (int)data.deltaY;
            inputSimulator.Mouse.VerticalScroll(deltaY);
        }

        private void HandleKeyInput(dynamic data)
        {
            try
            {
                string text = data.text.ToString();
                bool isSpecial = data.isSpecial ?? false;


                if (isSpecial)
                    HandleSpecialKey(text);
                else
                    inputSimulator.Keyboard.TextEntry(text);
            }
            catch { }
        }

        private void HandleKeyboardShortcut(string keyCommand)
        {
            string[] keys = keyCommand.Split('+');
            List<VirtualKeyCode> modifiers = [];
            VirtualKeyCode? mainKey = null;

            foreach (string key in keys)
            {
                switch (key.ToUpper())
                {
                    case "CTRL":
                        modifiers.Add(VirtualKeyCode.CONTROL);
                        break;
                    case "ALT":
                        modifiers.Add(VirtualKeyCode.MENU);
                        break;
                    case "SHIFT":
                        modifiers.Add(VirtualKeyCode.SHIFT);
                        break;
                    default:
                        if (Enum.TryParse<VirtualKeyCode>(key, true, out VirtualKeyCode parsed))
                            mainKey = parsed;
                        break;
                }
            }

            if (mainKey.HasValue)
            {
                if (modifiers.Count > 0)
                    inputSimulator.Keyboard.ModifiedKeyStroke(modifiers.ToArray(), mainKey.Value);
                else
                    inputSimulator.Keyboard.KeyPress(mainKey.Value);
            }
        }

        private void HandleSpecialKey(string keyCode)
        {
            switch (keyCode.ToUpper())
            {
                case "BACK":
                    inputSimulator.Keyboard.KeyPress(VirtualKeyCode.BACK);
                    return;
                case "RETURN":
                    inputSimulator.Keyboard.KeyPress(VirtualKeyCode.RETURN);
                    return;
                case "TAB":
                    inputSimulator.Keyboard.KeyPress(VirtualKeyCode.TAB);
                    return;
                case "ESCAPE":
                    inputSimulator.Keyboard.KeyPress(VirtualKeyCode.ESCAPE);
                    return;
                case "DELETE":
                    inputSimulator.Keyboard.KeyPress(VirtualKeyCode.DELETE);
                    return;
            }

            if (keyCode.Contains('+'))
                HandleKeyboardShortcut(keyCode);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            base.OnFormClosing(e);
            StopServer();
        }
    }
}