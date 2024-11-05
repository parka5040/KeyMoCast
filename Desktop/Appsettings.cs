using System.Configuration;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace RemoteControlServer
{
    public static class AppSettings
    {
        private static readonly string CONFIG_FILE = "RemoteControlServer.config";
        private static Configuration? _config = null;

        private static Configuration Config
        {
            get
            {
                if (_config == null)
                {
                    var map = new ExeConfigurationFileMap { ExeConfigFilename = CONFIG_FILE };
                    _config = ConfigurationManager.OpenMappedExeConfiguration(map, ConfigurationUserLevel.None);
                }
                return _config;
            }
        }

        public static int Port
        {
            get
            {
                var setting = Config.AppSettings.Settings["Port"];
                return setting != null && int.TryParse(setting.Value, out int port) ? port : 8080;
            }
            set
            {
                var settings = Config.AppSettings.Settings;
                if (settings["Port"] == null)
                    settings.Add("Port", value.ToString());
                else
                    settings["Port"].Value = value.ToString();
                Config.Save(ConfigurationSaveMode.Modified);
            }
        }

        public static string GetLocalIPAddress()
        {
            NetworkInterface[] interfaces = NetworkInterface.GetAllNetworkInterfaces();
            foreach (NetworkInterface nic in interfaces)
            {
                if (nic.OperationalStatus == OperationalStatus.Up &&
                    (nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet ||
                     nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211))
                {
                    IPInterfaceProperties ipProps = nic.GetIPProperties();
                    foreach (UnicastIPAddressInformation addr in ipProps.UnicastAddresses)
                    {
                        if (addr.Address.AddressFamily == AddressFamily.InterNetwork &&
                            !IPAddress.IsLoopback(addr.Address)) // Use only IPv4
                        {
                            return addr.Address.ToString();
                        }
                    }
                }
            }

            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
            {
                if (ip.AddressFamily == AddressFamily.InterNetwork)
                {
                    return ip.ToString();
                }
            }

            return "127.0.0.1"; // Last resort
        }

        public static List<string> GetAllLocalIPAddresses()
        {
            var addresses = new List<string>();

            try
            {
                NetworkInterface[] interfaces = NetworkInterface.GetAllNetworkInterfaces();
                foreach (NetworkInterface nic in interfaces)
                {
                    if (nic.OperationalStatus == OperationalStatus.Up &&
                        (nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet ||
                         nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211))
                    {
                        Console.WriteLine($"Found interface: {nic.Name} ({nic.NetworkInterfaceType})");
                        
                        IPInterfaceProperties ipProps = nic.GetIPProperties();
                        foreach (UnicastIPAddressInformation addr in ipProps.UnicastAddresses)
                        {
                            if (addr.Address.AddressFamily == AddressFamily.InterNetwork &&
                                !IPAddress.IsLoopback(addr.Address))
                            {
                                addresses.Add(addr.Address.ToString());
                                Console.WriteLine($"Added IP address: {addr.Address}");
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting network interfaces: {ex.Message}");
            }

            return addresses;
        }
    }
}