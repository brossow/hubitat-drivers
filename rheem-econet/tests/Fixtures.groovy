import org.codehaus.groovy.control.CompilerConfiguration

/**
 * Synthetic EcoNet equipment records and a driver loader.
 *
 * The record shapes follow what the API returns (and what pyeconet reads). Every
 * identifier is an obvious placeholder: never paste a real serial, device id or
 * account id into a fixture — debug logs from a real hub are input, not sample data.
 *
 * Observed on real hardware and worth keeping exact:
 *   @RUNNINGSTATUS  ''  ·  'Cooling to 69 °'  ·  'Heating to 73 °'  ·  'Fan Running'
 *   @FANSPEED enum  Auto, Low, Med.Lo, Medium, Med.Hi, High   (no @FANMODE on that unit)
 *   @MODE enum      index 0 = heat, 2 = auto on the verified unit
 */
class Fixtures {
    static final File DRIVER_DIR = new File(System.getProperty("econet.driverDir", ".."))

    static DriverBase load(String fileName) {
        def cc = new CompilerConfiguration()
        cc.scriptBaseClass = DriverBase.name
        def shell = new GroovyShell(DriverBase.classLoader, new Binding(), cc)
        def driver = (DriverBase) shell.parse(new File(DRIVER_DIR, fileName))
        driver.run()   // evaluates metadata { … }
        return driver
    }

    /** A driver wired to a fake API, with credentials set and debug logging on. */
    static Map connected(String fileName, List locations, Map extraSettings = [:]) {
        def api = new FakeEcoNet(locations: locations)
        def d = load(fileName)
        d.httpHandler = api.handler()
        d.settings.putAll([email: "user@example.com", password: "placeholder-password",
                           pollInterval: "5 minutes", tempUnit: "F", logEnable: true] + extraSettings)
        return [d: d, api: api]
    }

    static List account(List<Map> units, String place = "Home") {
        [[name: place, equiptments: units]]
    }

    static Map thermostat(Map o = [:]) {
        def modes = o.modes ?: ["Heating", "Cooling", "Auto", "Fan Only", "Off"]
        def fans  = o.fanSpeeds ?: ["Auto", "Low", "Med.Lo", "Medium", "Med.Hi", "High"]
        def t = [
            device_type    : "HVAC",
            device_name    : o.containsKey("deviceName") ? o.deviceName : "DEVICE-TSTAT-0001",
            serial_number  : o.containsKey("serial") ? o.serial : "00-00-00-00-00-00-aa-01-01",
            "@NAME"        : [value: o.name ?: "Thermostat"],
            "@MODE"        : [value: modes.indexOf(o.mode ?: "Cooling"), constraints: [enumText: modes]],
            "@FANSPEED"    : [value: fans.indexOf(o.fanSpeed ?: "Auto"), constraints: [enumText: fans]],
            "@SETPOINT"    : [value: o.temperature ?: 72],
            "@COOLSETPOINT": [value: o.cool ?: 75, constraints: [lowerLimit: 60, upperLimit: 92]],
            "@HEATSETPOINT": [value: o.heat ?: 68, constraints: [lowerLimit: 50, upperLimit: 90]],
            "@DEADBAND"    : [value: o.deadband ?: 2],
            "@RUNNINGSTATUS": o.containsKey("running") ? o.running : "",
            "@HUMIDITY"    : [value: 45],
            "@CONNECTED"   : true,
            "@AWAY"        : false,
        ]
        if (o.fanModes) {
            t["@FANMODE"] = [value: o.fanModes.indexOf(o.fanMode ?: o.fanModes[0]),
                             constraints: [enumText: o.fanModes]]
        }
        if (o.zones) t.zoning_devices = o.zones
        if (o.error) t.error = o.error
        return t
    }

    static Map waterHeater(Map o = [:]) {
        def modes = o.containsKey("modes") ? o.modes
                  : ["Off", "Energy Saving", "Heat Pump Only", "High Demand", "Electric Mode", "Vacation"]
        def w = [
            device_type  : "WH",
            device_name  : o.containsKey("deviceName") ? o.deviceName : "DEVICE-WH-0001",
            serial_number: o.containsKey("serial") ? o.serial : "00-00-00-00-00-00-bb-01-01",
            "@NAME"      : [value: o.name ?: "Water Heater"],
            "@TYPE"      : o.type ?: "heatPumpWaterHeater",
            "@SETPOINT"  : [value: o.setpoint ?: 120, constraints: [lowerLimit: 110, upperLimit: 140]],
            "@RUNNING"   : o.containsKey("running") ? o.running : "",
            "@HOTWATER"  : o.hotWater ?: "ic_tank_hundread_percent.png",
            "@CONNECTED" : true,
            "@AWAY"      : false,
        ]
        if (modes != null) {
            w["@MODE"] = [value: modes.indexOf(o.mode ?: "Energy Saving"), constraints: [enumText: modes]]
        }
        if (o.containsKey("enabled")) w["@ENABLED"] = [value: o.enabled]
        if (o.error) w.error = o.error
        return w
    }
}
