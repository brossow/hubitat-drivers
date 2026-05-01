import groovy.transform.Field

/**
 * Bambu Lab Printer — virtual device driver
 *
 * Stores printer state attributes updated by the Bambu Bridge app.
 * Dashboard tile HTML is served by the app — this driver just holds the data.
 *
 * Install order:
 *   1. Install this driver via Drivers Code
 *   2. Create a virtual device using this driver
 *   3. Install BambuBridgeApp via Apps Code (enable OAuth when prompted)
 *   4. Open the app, select this device — it will display the endpoint URL
 *   5. Paste that URL into HUBITAT_URL in your bridge .env file
 *   6. Add Attribute tiles to your dashboard using the "html" and "htmlAms" attributes
 */

@Field static final String VERSION = "1.0.0"

metadata {
    definition(
        name:      "Bambu Lab Printer",
        namespace: "brossow",
        author:    "Brent Rossow",
        importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bambu-printer.groovy",
    ) {
        capability "Sensor"

        command "updateStatus", [[name: "status", type: "JSON_OBJECT", description: "Parsed printer status map from bambu_bridge"]]

        attribute "printerState",     "string"   // IDLE | PREPARE | RUNNING | PAUSE | FINISH | FAILED
        attribute "printFile",        "string"
        attribute "printProgress",    "number"   // 0–100 %
        attribute "remainingTime",    "number"   // minutes
        attribute "currentLayer",     "number"
        attribute "totalLayers",      "number"
        attribute "nozzleTemp",       "number"   // °C
        attribute "nozzleTargetTemp", "number"
        attribute "bedTemp",          "number"
        attribute "bedTargetTemp",    "number"
        attribute "chamberTemp",      "number"
        attribute "speedLevel",       "string"   // Quiet | Standard | Sport | Ludicrous
        attribute "speedMagnitude",   "number"   // %
        attribute "printError",       "string"   // "0" = no error
        attribute "wifiSignal",       "string"
        attribute "amsSummary",       "string"   // compact text summary
        attribute "amsTrayNow",       "number"   // active tray global index (255 = none)
        attribute "cameraUrl",        "string"   // RTSP URL
        attribute "lastUpdate",       "string"   // ISO-8601 UTC
        attribute "html",             "string"   // iframe stub — loads status tile from app endpoint
        attribute "htmlAms",          "string"   // iframe stub — loads AMS tile from app endpoint
        attribute "driverVersion",    "string"
    }

    preferences {
        input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    log.info "${device.displayName} installed (v${VERSION})"
    sendEvent(name: "driverVersion", value: VERSION)
}

def updated() {
    log.info "${device.displayName} updated (v${VERSION})"
    sendEvent(name: "driverVersion", value: VERSION)
}

def updateStatus(Map s) {
    if (logEnable) log.debug "updateStatus: ${s}"
    s.each { key, value -> sendEvent(name: key, value: value) }
}
