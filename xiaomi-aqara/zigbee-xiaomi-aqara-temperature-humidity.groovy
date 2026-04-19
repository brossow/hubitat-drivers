/**
 *  Copyright 2020 Markus Liljergren (https://oh-lalabs.com)
 *
 *  Version: v1.2.0
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 *  v1.2.0 — Community fork: https://github.com/brossow/hubitat-drivers/tree/main/xiaomi-aqara
 *  Cleanup, dead-code removal, and improvements by Brent Rossow
 *  Original driver by Markus Liljergren (oh-lalabs.com)
 *
 */

import hubitat.helper.HexUtils

metadata {
    definition (
        name: "Zigbee - Xiaomi/Aqara Temperature & Humidity Sensor",
        namespace: "community",
        author: "Markus Liljergren (community fork by Brent Rossow)",
        filename: "zigbee-xiaomi-aqara-temperature-humidity",
        importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/xiaomi-aqara/zigbee-xiaomi-aqara-temperature-humidity.groovy"
    ) {
        capability "Sensor"
        capability "PresenceSensor"
        capability "Initialize"
        capability "Refresh"

        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "PressureMeasurement"

        attribute "driver", "string"
        attribute "lastCheckin", "Date"
        attribute "lastCheckinEpoch", "number"
        attribute "notPresentCounter", "number"
        attribute "restoredCounter", "number"
        attribute "batteryLastReplaced", "String"
        attribute "batteryVoltage", "number"
        attribute "absoluteHumidity", "number"

        command "resetBatteryReplacedDate"
        command "resetRestoredCounter"
        command "forceRecoveryMode", [[name:"Minutes*", type: "NUMBER", description: "Maximum minutes to run in Recovery Mode"]]

        fingerprint deviceJoinName: "Xiaomi Temperature & Humidity Sensor (WSDCGQ01LM)", model: "lumi.sens", profileId: "0104", endpointId: 01, inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI"
        fingerprint deviceJoinName: "Xiaomi Temperature & Humidity Sensor (WSDCGQ01LM)", model: "lumi.sensor_ht", profileId: "0104", endpointId: 01, inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI"
        fingerprint deviceJoinName: "Aqara Temperature, Humidity & Pressure Sensor (WSDCGQ11LM)", model: "lumi.weather", modelType: "Aqara WSDCGQ11LM", profileId: "0104", endpointId: 01, application: 03, inClusters: "0000,0003,FFFF,0402,0403,0405", outClusters: "0000,0004,FFFF", manufacturer: "LUMI"
        fingerprint deviceJoinName: "Keen Temperature, Humidity & Pressure Sensor (RS-THP-MP-1.0)", model: "RS-THP-MP-1.0", modelType: "Keen RS-THP-MP-1.0", endpointId: 01, application: 0x0A, inClusters: "0000,0003,0001,0020", outClusters: "0000,0004,0003,0005,0019,0402,0405,0403,0020", manufacturer: "Keen Home"
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0402,0405,0403,0001", outClusters:"0019", model:"lumi.sensor_ht.agl02", manufacturer:"LUMI"
    }

    preferences {
        input(name: "debugLogging", type: "bool", title: titleDiv("Enable debug logging"), description: "" + defaultCSS(), defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
        input(name: "infoLogging", type: "bool", title: titleDiv("Enable info logging"), description: "", defaultValue: true, submitOnChange: true, displayDuringSetup: false, required: false)
        input(name: "lastCheckinEnable", type: "bool", title: titleDiv("Enable Last Checkin Date"), description: descDiv("Records Date events if enabled"), defaultValue: true)
        input(name: "lastCheckinEpochEnable", type: "bool", title: titleDiv("Enable Last Checkin Epoch"), description: descDiv("Records Epoch events if enabled"), defaultValue: false)
        input(name: "presenceEnable", type: "bool", title: titleDiv("Enable Presence"), description: descDiv("Enables Presence to indicate if the device has sent data within the last 3 hours (REQUIRES at least one of the Checkin options to be enabled)"), defaultValue: true)
        input(name: "presenceWarningEnable", type: "bool", title: titleDiv("Enable Presence Warning"), description: descDiv("Enables Presence Warnings in the Logs (default: true)"), defaultValue: true)
        input(name: "recoveryMode", type: "enum", title: titleDiv("Recovery Mode"), description: descDiv("Select Recovery mode type (default: Normal)<br/>NOTE: The \"Insane\" and \"Suicidal\" modes may destabilize your mesh if run on more than a few devices at once!"), options: ["Disabled", "Slow", "Normal", "Insane", "Suicidal"], defaultValue: "Normal")
        input(name: "vMinSetting", type: "decimal", title: titleDiv("Battery Minimum Voltage"), description: descDiv("Voltage when battery is considered to be at 0% (default = 2.5V)"), defaultValue: "2.5", range: "2.1..2.8")
        input(name: "vMaxSetting", type: "decimal", title: titleDiv("Battery Maximum Voltage"), description: descDiv("Voltage when battery is considered to be at 100% (default = 3.0V)"), defaultValue: "3.0", range: "2.9..3.4")
        input(name: "tempUnitDisplayed", type: "enum", title: titleDiv("Displayed Temperature Unit"), description: "", defaultValue: "0", required: true, multiple: false, options:[["0":"System Default"], ["1":"Celsius"], ["2":"Fahrenheit"], ["3":"Kelvin"]])
        input(name: "tempOffset", type: "decimal", title: titleDiv("Temperature Offset"), description: descDiv("Adjust the temperature by this many degrees."), displayDuringSetup: true, required: false, range: "*..*")
        input(name: "tempRes", type: "enum", title: titleDiv("Temperature Resolution"), description: descDiv("Temperature sensor resolution (0..2 = maximum number of decimal places, default: 1)<br/>NOTE: If the 2nd decimal is a 0 (eg. 24.70) it will show without the last decimal (eg. 24.7)."), options: ["0", "1", "2"], defaultValue: "1", displayDuringSetup: true, required: false)
        input(name: "tempVariance", type: "decimal", title: titleDiv("Temperature Reporting Threshold"), description: descDiv("Only send a temperature event if the reading differs from the last by at least this many degrees (default: 0.2)."), defaultValue: "0.2", range: "0..10", required: false)
        input(name: "humidityOffset", type: "decimal", title: titleDiv("Humidity Offset"), description: descDiv("Adjust the humidity by this many percent."), displayDuringSetup: true, required: false, range: "*..*")
        input(name: "humidityRes", type: "enum", title: titleDiv("Humidity Resolution"), description: descDiv("Humidity sensor resolution (0..1 = maximum number of decimal places, default: 1)"), options: ["0", "1"], defaultValue: "1")
        input(name: "humidityVariance", type: "decimal", title: titleDiv("Humidity Reporting Threshold"), description: descDiv("Only send a humidity event if the reading differs from the last by at least this many percent (default: 0.5)."), defaultValue: "0.5", range: "0..20", required: false)
        input(name: "reportAbsoluteHumidity", type: "bool", title: titleDiv("Report Absolute Humidity"), description: descDiv("Also report Absolute Humidity in g/m³. Default = Disabled"), defaultValue: false)
        if(getDeviceDataByName('hasPressure') == "True") {
            input(name: "pressureUnitConversion", type: "enum", title: titleDiv("Displayed Pressure Unit"), description: descDiv("(default: kPa)"), options: ["mbar", "kPa", "inHg", "mmHg", "atm"], defaultValue: "kPa")
            input(name: "pressureRes", type: "enum", title: titleDiv("Pressure Resolution"), description: descDiv("Pressure sensor resolution (0..2 = maximum number of decimal places, default: default)"), options: ["default", "0", "1", "2"], defaultValue: "default")
            input(name: "pressureOffset", type: "decimal", title: titleDiv("Pressure Offset"), description: descDiv("Adjust the pressure value by this much."), displayDuringSetup: true, required: false, range: "*..*")
        }
    }
}

/* ===== DRIVER LIFECYCLE ===== */

ArrayList<String> refresh() {
    logging("refresh() model='${getDeviceDataByName('model')}'", 10)

    getDriverVersion()
    configurePresence()
    startCheckinMonitor()
    resetBatteryReplacedDate(forced=false)
    scheduleLogsOff(noLogWarning=true)
    state.remove("prefsSetCount")

    // Normalize legacy "lumi.sens" model string before canonical lookup
    if(getDeviceDataByName('model') == "lumi.sens") updateDataValue('model', 'lumi.sensor_ht')

    String model = normalizeModel(newModelToSet=null, acceptedModels=[
        "lumi.sensor_ht.agl02",
        "lumi.sensor_ht",
        "lumi.weather",
        "RS-THP-MP-1.0"
    ])

    if(model == "lumi.weather" || model == "lumi.sensor_ht.agl02" || model == "RS-THP-MP-1.0") {
        updateDataValue("hasPressure", "True")
    } else {
        updateDataValue("hasPressure", "False")
    }

    ArrayList<String> cmd = []

    if(model == "lumi.sensor_ht.agl02") {
        bindT1Clusters()
        cmd = []
    }

    logging("refresh cmd: $cmd", 1)
    logging("Recovery Mode set as: $recoveryMode", 100)
    return cmd
}

void bindT1Clusters() {
    ArrayList<String> cmd = []
    String endpoint = '01'
    cmd += ["zdo bind ${device.deviceNetworkId} 0x$endpoint 0x01 0x0402 {${device.zigbeeId}} {}", "delay 187",]
    cmd += ["zdo bind ${device.deviceNetworkId} 0x$endpoint 0x01 0x0403 {${device.zigbeeId}} {}", "delay 188",]
    cmd += ["zdo bind ${device.deviceNetworkId} 0x$endpoint 0x01 0x0405 {${device.zigbeeId}} {}", "delay 189",]
    cmd += zigbee.readAttribute(0x0402, 0x0000)
    cmd += zigbee.readAttribute(0x0403, 0x0000)
    cmd += zigbee.readAttribute(0x0405, 0x0000)
    sendZigbeeCommands(cmd)
}

void initialize() {
    logging("initialize()", 100)
    unschedule()
    refresh()
}

void installed() {
    logging("installed()", 100)
    refresh()
}

void updated() {
    logging("updated()", 100)
    refresh()
}

ArrayList<String> parse(String description) {
    ArrayList<String> cmd = []
    Map msgMap = null
    if(description.indexOf('encoding: 4C') >= 0) {
        msgMap = zigbee.parseDescriptionAsMap(description.replace('encoding: 4C', 'encoding: F2'))
        msgMap = unpackStruct(msgMap)
    } else if(description.indexOf('attrId: FF01, encoding: 42') >= 0) {
        msgMap = zigbee.parseDescriptionAsMap(description.replace('encoding: 42', 'encoding: F2'))
        msgMap["encoding"] = "41"
        msgMap["value"] = parseXiaomiStruct(msgMap["value"], isFCC0=false, hasLength=true)
    } else {
        if(description.indexOf('encoding: 42') >= 0) {
            List values = description.split("value: ")[1].split("(?<=\\G..)")
            String fullValue = values.join()
            Integer zeroIndex = values.indexOf("01")
            if(zeroIndex > -1) {
                msgMap = zigbee.parseDescriptionAsMap(description.replace(fullValue, values.take(zeroIndex).join()))
                values = values.drop(zeroIndex + 3)
                msgMap["additionalAttrs"] = [
                    ["encoding": "41",
                    "value": parseXiaomiStruct(values.join(), isFCC0=false, hasLength=true)]
                ]
            } else {
                msgMap = zigbee.parseDescriptionAsMap(description)
            }
        } else {
            msgMap = zigbee.parseDescriptionAsMap(description)
        }
        if(msgMap.containsKey("encoding") && msgMap.containsKey("value") && msgMap["encoding"] != "41" && msgMap["encoding"] != "42") {
            msgMap["valueParsed"] = decodeZigbeeData(msgMap["value"], msgMap["encoding"])
        }
        if(msgMap == [:] && description.indexOf("zone") == 0) {
            msgMap["type"] = "zone"
            java.util.regex.Matcher zoneMatcher = description =~ /.*zone.*status.*0x(?<status>([0-9a-fA-F][0-9a-fA-F])+).*extended.*status.*0x(?<statusExtended>([0-9a-fA-F][0-9a-fA-F])+).*/
            if(zoneMatcher.matches()) {
                msgMap["parsed"] = true
                msgMap["status"] = zoneMatcher.group("status")
                msgMap["statusInt"] = Integer.parseInt(msgMap["status"], 16)
                msgMap["statusExtended"] = zoneMatcher.group("statusExtended")
                msgMap["statusExtendedInt"] = Integer.parseInt(msgMap["statusExtended"], 16)
            } else {
                msgMap["parsed"] = false
            }
        }
    }
    logging("msgMap: ${msgMap}", 100)

    switch(msgMap["cluster"] + '_' + msgMap["attrId"]) {
        case "0000_0005":
            logging("Reset button pressed/message requested by hourly checkin - description:${description} | parseMap:${msgMap}", 1)
            if(msgMap["value"] == "lumi.sens") msgMap["value"] = "lumi.sensor_ht"
            normalizeModel(newModelToSet=msgMap["value"])
            // Keep hasPressure consistent when model is received via parse
            String updatedModel = getDeviceDataByName('model')
            if(updatedModel == "lumi.weather" || updatedModel == "lumi.sensor_ht.agl02" || updatedModel == "RS-THP-MP-1.0") {
                updateDataValue("hasPressure", "True")
            } else {
                updateDataValue("hasPressure", "False")
            }
            break

        case "0000_0004":
            logging("Manufacturer Name Received (from readAttribute command) - description:${description} | parseMap:${msgMap}", 1)
            sendZigbeeCommands(zigbee.readAttribute(0x0402, 0x0000))
            break

        case "0001_0020":
            logging("Battery Voltage Received - description:${description} | parseMap:${msgMap}", 1)
            sendBatteryEvent(msgMap["valueParsed"] / 10.0)
            break

        case "0402_0000":
            logging("XIAOMI/AQARA TEMPERATURE EVENT - description:${description} | parseMap:${msgMap}", 1)
            sendTemperatureEvent(msgMap['valueParsed'])
            break

        case "0403_0000":
        case "0403_0020":
            logging("AQARA PRESSURE EVENT - description:${description} | parseMap:${msgMap}", 1)
            sendPressureEvent(msgMap)
            break

        case "0405_0000":
            logging("XIAOMI/AQARA HUMIDITY EVENT - description:${description} | parseMap:${msgMap}", 1)
            sendHumidityEvent(msgMap['valueParsed'])
            break

        case "0000_FF01":
            logging("KNOWN event (Xiaomi/Aqara specific data structure with battery data - 42 - hourly checkin) - description:${description} | parseMap:${msgMap}", 100)
            if(msgMap["value"].containsKey("battery")) {
                sendBatteryEvent(msgMap["value"]["battery"] / 1000.0)
            }
            if(msgMap["value"].containsKey("temperature")) {
                sendTemperatureEvent(msgMap["value"]["temperature"])
            }
            if(msgMap["value"].containsKey("humidity")) {
                // Use a fixed 1.0% variance for the hourly checkin path — these fire
                // unconditionally on a schedule rather than on change, so a slightly
                // higher threshold prevents noise from tiny floating-point drift
                sendHumidityEvent(msgMap["value"]["humidity"], 1.00)
            }
            if(msgMap["value"].containsKey("pressure")) {
                sendPressureEvent([
                    "attrId": "0000",
                    "valueParsed": msgMap["value"]["pressure"] / 100.0
                ])
            }
            logging("Sending request to cluster 0x0000 for attribute 0x0005 (response to attrId: 0x${msgMap["attrId"]}) 1", 1)
            sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0005))
            break

        default:
            switch(msgMap["clusterId"]) {
                case "0003":
                    logging("Button press, re-binding for temperature, humidity and pressure events.", 100)
                    bindT1Clusters()
                    break
                case "0013":
                    logging("MULTISTATE CLUSTER EVENT - description:${description} | parseMap:${msgMap}", 100)
                    break
                case "0402":
                case "0403":
                case "0405":
                case "8021":
                case "8032":
                    break
                default:
                    log.warn "Unhandled Zigbee event — please report at https://github.com/brossow/hubitat-drivers/tree/main/xiaomi-aqara — description:${description} | msgMap:${msgMap}"
            }
            break
    }

    if(checkinIsRecent(maximumMinutesBetweenEvents=90) == false) {
        List<String> restoreCmd = zigbeeReadAttribute(CLUSTER_BASIC, 0x0005, [:], delay=68)
        logging("Restoring bind settings", 100)
        restoreCmd += ["zdo bind ${device.deviceNetworkId} 0x01 0x01 0x0000 {${device.zigbeeId}} {}", "delay 69",]
        restoreCmd += ["zdo bind ${device.deviceNetworkId} 0x01 0x01 0x0402 {${device.zigbeeId}} {}", "delay 71",]
        restoreCmd += ["zdo bind ${device.deviceNetworkId} 0x01 0x01 0x0403 {${device.zigbeeId}} {}", "delay 72",]
        restoreCmd += ["zdo bind ${device.deviceNetworkId} 0x01 0x01 0x0405 {${device.zigbeeId}} {}", "delay 73",]
        sendZigbeeCommands(restoreCmd)
    }
    sendCheckinEvent(minimumMinutesToRepeat=30)

    msgMap = null
    return cmd
}

void pollDevice() {
    logging("pollDevice() T&H", 1)
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
}

/* ===== DRIVER METADATA ===== */

private String getDriverVersion() {
    String version = "v1.2.0"
    logging("getDriverVersion() = ${version}", 100)
    sendEvent(name: "driver", value: version)
    updateDataValue('driver', version)
    return version
}

/* ===== LOGGING ===== */

private boolean logging(message, level) {
    boolean didLogging = false
    Integer logLevelLocal = 0
    if (infoLogging == null || infoLogging == true) {
        logLevelLocal = 100
    }
    if (debugLogging == true) {
        logLevelLocal = 1
    }
    if (logLevelLocal != 0){
        switch (logLevelLocal) {
            case 1:
                if (level >= 1 && level < 99) {
                    log.debug "$message"
                    didLogging = true
                } else if (level == 100) {
                    log.info "$message"
                    didLogging = true
                }
                break
            case 100:
                if (level == 100) {
                    log.info "$message"
                    didLogging = true
                }
                break
        }
    }
    return didLogging
}

/* ===== ZIGBEE HELPERS ===== */

private getCLUSTER_BASIC() { 0x0000 }

ArrayList<String> zigbeeReadAttribute(Integer cluster, Integer attributeId, Map additionalParams = [:], int delay = 205) {
    ArrayList<String> cmd = zigbee.readAttribute(cluster, attributeId, additionalParams, delay)
    cmd[0] = cmd[0].replace('0xnull', '0x01')
    return cmd
}

ArrayList<String> zigbeeReadAttribute(Integer endpoint, Integer cluster, Integer attributeId, int delay = 206) {
    ArrayList<String> cmd = ["he rattr 0x${device.deviceNetworkId} ${endpoint} 0x${HexUtils.integerToHexString(cluster, 2)} 0x${HexUtils.integerToHexString(attributeId, 2)} {}", "delay $delay"]
    return cmd
}

void sendZigbeeCommands(ArrayList<String> cmd) {
    logging("sendZigbeeCommands(cmd=$cmd)", 1)
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
        allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
    }
    sendHubCommand(allActions)
}

String normalizeModel(String newModelToSet=null, List<String> acceptedModels=null) {
    String model = newModelToSet != null ? newModelToSet : getDeviceDataByName('model')
    model = model == null ? "null" : model
    String newModel = model.replaceAll("[^A-Za-z0-9.\\-_ ]", "")
    if(acceptedModels != null) {
        boolean found = false
        acceptedModels.each {
            if(found == false && newModel == it) {
                found = true
            }
        }
    }
    logging("dirty model = $model, clean model=$newModel", 1)
    updateDataValue('model', newModel)
    return newModel
}

void resetBatteryReplacedDate(boolean forced=true) {
    if(forced == true || device.currentValue('batteryLastReplaced') == null) {
        sendEvent(name: "batteryLastReplaced", value: new Date().format('yyyy-MM-dd HH:mm:ss'))
    }
}

void sendBatteryEvent(BigDecimal vCurrent) {
    BigDecimal vMin = vMinSetting == null ? 2.5 : vMinSetting
    BigDecimal vMax = vMaxSetting == null ? 3.0 : vMaxSetting

    BigDecimal bat = 0
    if(vMax - vMin > 0) {
        bat = ((vCurrent - vMin) / (vMax - vMin)) * 100.0
    } else {
        bat = 100
    }
    bat = bat.setScale(0, BigDecimal.ROUND_HALF_UP)
    bat = bat > 100 ? 100 : bat
    bat = bat < 0 ? 0 : bat

    vCurrent = vCurrent.setScale(3, BigDecimal.ROUND_HALF_UP)

    logging("Battery event: $bat% (V = $vCurrent)", 1)
    sendEvent(name:"battery", value: bat, unit: "%", isStateChange: false)
    sendEvent(name:"batteryVoltage", value: vCurrent, unit: "V", isStateChange: false)
}

Map unpackStruct(Map msgMap, String originalEncoding="4C") {
    msgMap['encoding'] = originalEncoding
    List<String> values = msgMap['value'].split("(?<=\\G..)")
    logging("unpackStruct() values=$values", 1)
    Integer numElements = Integer.parseInt(values.take(2).reverse().join(), 16)
    values = values.drop(2)
    List r = []
    Integer cType = null
    List ret = null
    while(values != []) {
        cType = Integer.parseInt(values.take(1)[0], 16)
        values = values.drop(1)
        ret = convertStructValueToList(values, cType)
        r += ret[0]
        values = ret[1]
    }
    if(r.size() != numElements) throw new Exception("The STRUCT specifies $numElements elements, found ${r.size()}!")
    msgMap['value'] = r
    return msgMap
}

Map parseXiaomiStruct(String xiaomiStruct, boolean isFCC0=false, boolean hasLength=false) {
    Map tags = [
        '01': 'battery',
        '03': 'deviceTemperature',
        '04': 'unknown1',
        '05': 'RSSI_dB',
        '06': 'LQI',
        '07': 'unknown2',
        '08': 'unknown3',
        '09': 'unknown4',
        '0A': 'routerid',
        '0B': 'unknown5',
        '0C': 'unknown6',
        '6429': 'temperature',
        '6410': 'openClose',
        '6420': 'curtainPosition',
        '6521': 'humidity',
        '6510': 'switch2',
        '66': 'pressure',
        '6E': 'unknown10',
        '6F': 'unknown11',
        '95': 'consumption',
        '96': 'voltage',
        '98': 'power',
        '9721': 'gestureCounter1',
        '9739': 'consumption',
        '9821': 'gestureCounter2',
        '9839': 'power',
        '99': 'gestureCounter3',
        '9A21': 'gestureCounter4',
        '9A20': 'unknown7',
        '9A25': 'accelerometerXYZ',
        '9B': 'unknown9',
    ]
    if(isFCC0 == true) {
        tags['05'] = 'numBoots'
        tags['6410'] = 'onOff'
        tags['95'] = 'current'
    }

    List<String> values = xiaomiStruct.split("(?<=\\G..)")
    if(hasLength == true) values = values.drop(1)
    Map r = [:]
    r["raw"] = [:]
    String cTag = null
    String cTypeStr = null
    Integer cType = null
    String cKey = null
    List ret = null
    while(values != []) {
        cTag = values.take(1)[0]
        values = values.drop(1)
        cTypeStr = values.take(1)[0]
        cType = Integer.parseInt(cTypeStr, 16)
        values = values.drop(1)
        if(tags.containsKey(cTag+cTypeStr)) {
            cKey = tags[cTag+cTypeStr]
        } else if(tags.containsKey(cTag)) {
            cKey = tags[cTag]
        } else {
            cKey = "unknown${cTag}${cTypeStr}"
            log.warn("Unknown Xiaomi struct tag: 0x$cTag (type: 0x$cTypeStr) — please report at https://github.com/brossow/hubitat-drivers/tree/main/xiaomi-aqara (struct: $xiaomiStruct)")
        }
        ret = convertStructValue(r, values, cType, cKey, cTag)
        r = ret[0]
        values = ret[1]
    }
    return r
}

def decodeZigbeeData(String value, String cTypeStr, boolean reverseBytes=true) {
    List values = value.split("(?<=\\G..)")
    values = reverseBytes == true ? values.reverse() : values
    Integer cType = Integer.parseInt(cTypeStr, 16)
    Map rMap = [:]
    rMap['raw'] = [:]
    List ret = convertStructValue(rMap, values, cType, "NA", "NA")
    return ret[0]["NA"]
}

List convertStructValueToList(List values, Integer cType) {
    Map rMap = [:]
    rMap['raw'] = [:]
    List ret = convertStructValue(rMap, values, cType, "NA", "NA")
    return [ret[0]["NA"], ret[1]]
}

List convertStructValue(Map r, List values, Integer cType, String cKey, String cTag) {
    String cTypeStr = cType != null ? integerToHexString(cType, 1) : null
    switch(cType) {
        case 0x10:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16) != 0
            values = values.drop(1)
            break
        case 0x18:
        case 0x20:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(1)
            break
        case 0x19:
        case 0x21:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x1A:
        case 0x22:
            r["raw"][cKey] = values.take(3).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(3)
            break
        case 0x1B:
        case 0x23:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(4)
            break
        case 0x1C:
        case 0x24:
            r["raw"][cKey] = values.take(5).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(5)
            break
        case 0x1D:
        case 0x25:
            r["raw"][cKey] = values.take(6).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(6)
            break
        case 0x1E:
        case 0x26:
            r["raw"][cKey] = values.take(7).reverse().join()
            r[cKey] = Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(7)
            break
        case 0x1F:
        case 0x27:
            r["raw"][cKey] = values.take(8).reverse().join()
            r[cKey] = new BigInteger(r["raw"][cKey], 16)
            values = values.drop(8)
            break
        case 0x28:
            r["raw"][cKey] = values.take(1).reverse().join()
            r[cKey] = convertToSignedInt8(Integer.parseInt(r["raw"][cKey], 16))
            values = values.drop(1)
            break
        case 0x29:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = (Integer) (short) Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x2B:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = (Integer) Long.parseLong(r["raw"][cKey], 16)
            values = values.drop(4)
            break
        case 0x30:
            r["raw"][cKey] = values.take(1)[0]
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(1)
            break
        case 0x31:
            r["raw"][cKey] = values.take(2).reverse().join()
            r[cKey] = Integer.parseInt(r["raw"][cKey], 16)
            values = values.drop(2)
            break
        case 0x39:
            r["raw"][cKey] = values.take(4).reverse().join()
            r[cKey] = parseSingleHexToFloat(r["raw"][cKey])
            values = values.drop(4)
            break
        case 0x42:
            Integer strLength = Integer.parseInt(values.take(1)[0], 16)
            values = values.drop(1)
            r["raw"][cKey] = values.take(strLength)
            r[cKey] = r["raw"][cKey].collect {
                (char)(int) Integer.parseInt(it, 16)
            }.join()
            values = values.drop(strLength)
            break
        default:
            throw new Exception("The Struct used an unrecognized type: $cTypeStr ($cType) for tag 0x$cTag with key $cKey (values: $values, map: $r)")
    }
    return [r, values]
}

Float parseSingleHexToFloat(String singleHex) {
    return Float.intBitsToFloat(Long.valueOf(singleHex, 16).intValue())
}

Integer convertToSignedInt8(Integer signedByte) {
    Integer sign = signedByte & (1 << 7)
    return (signedByte & 0x7f) * (sign != 0 ? -1 : 1)
}

String integerToHexString(Integer value, Integer minBytes, boolean reverse=false) {
    if(reverse == true) {
        return HexUtils.integerToHexString(value, minBytes).split("(?<=\\G..)").reverse().join()
    } else {
        return HexUtils.integerToHexString(value, minBytes)
    }
}

/* ===== PRESENCE & RECOVERY ===== */

Integer maxEventMinutes(BigDecimal forcedMinutes=null) {
    Integer mbe = null
    if(forcedMinutes == null && (state.forcedMinutes == null || state.forcedMinutes == 0)) {
        mbe = MINUTES_BETWEEN_EVENTS == null ? 90 : MINUTES_BETWEEN_EVENTS
    } else {
        mbe = forcedMinutes != null ? forcedMinutes.intValue() : state.forcedMinutes.intValue()
    }
    return mbe
}

void reconnectEvent(BigDecimal forcedMinutes=null) {
    recoveryEvent(forcedMinutes)
}

void disableRecoveryDueToBug() {
    log.warn("Stopping Recovery feature due to Platform bug! Disabling the feature in Preferences. To use it again when the platform is stable, Enable it in Device Preferences.")
    unschedule('recoveryEvent')
    unschedule('reconnectEvent')
    device.updateSetting('recoveryMode', 'Disabled')
}

void recoveryEvent(BigDecimal forcedMinutes=null) {
    try {
        pollDevice()
    } catch(Exception e) {
        logging("recoveryEvent()", 1)
        sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
    }
    try {
        checkPresence(displayWarnings=false)
        Integer mbe = maxEventMinutes(forcedMinutes=forcedMinutes)
        if(checkinIsRecent(maximumMinutesBetweenEvents=mbe, displayWarnings=false) == true) {
            if(presenceWarningEnable == null || presenceWarningEnable == true) log.warn("Event interval normal, recovery mode DEACTIVATED!")
            unschedule('recoveryEvent')
            unschedule('reconnectEvent')
        }
    } catch(Exception e) {
        disableRecoveryDueToBug()
    }
}

void scheduleRecovery(BigDecimal forcedMinutes=null) {
    Random rnd = new Random()
    switch(recoveryMode) {
        case "Suicidal":
            schedule("${rnd.nextInt(15)}/15 * * * * ? *", 'recoveryEvent')
            break
        case "Insane":
            schedule("${rnd.nextInt(30)}/30 * * * * ? *", 'recoveryEvent')
            break
        case "Slow":
            schedule("${rnd.nextInt(59)} ${rnd.nextInt(3)}/3 * * * ? *", 'recoveryEvent')
            break
        case null:
        case "Normal":
        default:
            schedule("${rnd.nextInt(59)} ${rnd.nextInt(2)}/2 * * * ? *", 'recoveryEvent')
            break
    }
    recoveryEvent(forcedMinutes=forcedMinutes)
}

void checkCheckinInterval(boolean displayWarnings=true) {
    logging("recoveryMode: $recoveryMode", 1)
    if(recoveryMode == "Disabled") {
        unschedule('checkCheckinInterval')
    } else {
        initCounters()
        Integer mbe = maxEventMinutes()
        try {
            if(checkinIsRecent(maximumMinutesBetweenEvents=mbe) == false) {
                recoveryMode = recoveryMode == null ? "Normal" : recoveryMode
                if(displayWarnings == true && (presenceWarningEnable == null || presenceWarningEnable == true)) log.warn("Event interval INCORRECT, recovery mode ($recoveryMode) ACTIVE! If this is shown every hour for the same device and doesn't go away after three times, the device has probably fallen off and require a quick press of the reset button or possibly even re-pairing. It MAY also return within 24 hours, so patience MIGHT pay off.")
                scheduleRecovery()
            }
        } catch(Exception e) {
            disableRecoveryDueToBug()
        }
        sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
    }
}

void startCheckinMonitor() {
    logging("startCheckinMonitor()", 1)
    if(recoveryMode != "Disabled") {
        logging("Recovery feature ENABLED", 100)
        Random rnd = new Random()
        schedule("${rnd.nextInt(59)} ${rnd.nextInt(59)}/59 * * * ? *", 'checkCheckinInterval')
        checkCheckinInterval(displayWarnings=true)
    } else {
        logging("Recovery feature DISABLED", 100)
        unschedule('checkCheckinInterval')
        unschedule('recoveryEvent')
        unschedule('reconnectEvent')
    }
}

void forceRecoveryMode(BigDecimal minutes) {
    minutes = minutes == null || minutes < 0 ? 0 : minutes
    Integer minutesI = minutes.intValue()
    logging("forceRecoveryMode(minutes=$minutesI) ", 1)
    if(minutesI == 0) {
        disableForcedRecoveryMode()
    } else if(checkinIsRecent(maximumMinutesBetweenEvents=minutesI) == false) {
        recoveryMode = recoveryMode == null ? "Normal" : recoveryMode
        if(presenceWarningEnable == null || presenceWarningEnable == true) log.warn("Forced recovery mode ($recoveryMode) ACTIVATED!")
        state.forcedMinutes = minutes
        runIn(minutesI * 60, 'disableForcedRecoveryMode')
        scheduleRecovery(forcedMinutes=minutes)
    } else {
        log.warn("Forced recovery mode NOT activated since we already have a checkin event during the last $minutesI minute(s)!")
    }
}

void disableForcedRecoveryMode() {
    state.forcedMinutes = 0
    unschedule('recoveryEvent')
    unschedule('reconnectEvent')
    if(presenceWarningEnable == null || presenceWarningEnable == true) log.warn("Forced recovery mode DEACTIVATED!")
}

void scheduleLogsOff(boolean noLogWarning=false) {
    if (debugLogging == true) {
        if(noLogWarning==false) {
            log.warn "Debug logging will be disabled in 30 minutes..."
        }
        runIn(1800, "logsOff")
    }
}

void logsOff() {
    log.warn "Debug logging disabled..."
    device.clearSetting("logLevel")
    device.removeSetting("logLevel")
    device.updateSetting("logLevel", "0")
    state?.settings?.remove("logLevel")
    device.clearSetting("debugLogging")
    device.removeSetting("debugLogging")
    device.updateSetting("debugLogging", "false")
    state?.settings?.remove("debugLogging")
}

boolean isValidDate(String dateFormat, String dateString) {
    try {
        Date.parse(dateFormat, dateString)
    } catch (e) {
        return false
    }
    return true
}

Integer minRepeatMinutes(Integer minimumMinutesToRepeat=55) {
    Integer mmr = null
    if(state.forcedMinutes == null || state.forcedMinutes == 0) {
        mmr = minimumMinutesToRepeat
    } else {
        mmr = state.forcedMinutes - 1 < 1 ? 1 : state.forcedMinutes.intValue() - 1
    }
    return mmr
}

boolean sendCheckinEvent(Integer minimumMinutesToRepeat=55) {
    boolean r = false
    Integer mmr = minRepeatMinutes(minimumMinutesToRepeat=minimumMinutesToRepeat)
    if (lastCheckinEnable == true || lastCheckinEnable == null) {
        String lastCheckinVal = device.currentValue('lastCheckin')
        if(lastCheckinVal == null || isValidDate('yyyy-MM-dd HH:mm:ss', lastCheckinVal) == false || now() >= Date.parse('yyyy-MM-dd HH:mm:ss', lastCheckinVal).getTime() + (mmr * 60 * 1000)) {
            r = true
            sendEvent(name: "lastCheckin", value: new Date().format('yyyy-MM-dd HH:mm:ss'))
            logging("Updated lastCheckin", 1)
        }
    }
    if (lastCheckinEpochEnable == true) {
        if(device.currentValue('lastCheckinEpoch') == null || now() >= device.currentValue('lastCheckinEpoch').toLong() + (mmr * 60 * 1000)) {
            r = true
            sendEvent(name: "lastCheckinEpoch", value: now())
            logging("Updated lastCheckinEpoch", 1)
        }
    }
    if(r == true) markPresent()
    return r
}

Long secondsSinceCheckin() {
    Long r = null
    if (lastCheckinEnable == true || lastCheckinEnable == null) {
        String lastCheckinVal = device.currentValue('lastCheckin')
        if(lastCheckinVal == null || isValidDate('yyyy-MM-dd HH:mm:ss', lastCheckinVal) == false) {
            logging("No VALID lastCheckin event available! This should be resolved by itself within 1 or 2 hours and is perfectly NORMAL as long as the same device don't get this multiple times per day...", 100)
            r = -1
        } else {
            r = (now() - Date.parse('yyyy-MM-dd HH:mm:ss', lastCheckinVal).getTime()) / 1000
        }
    }
    if (lastCheckinEpochEnable == true) {
        if(device.currentValue('lastCheckinEpoch') == null) {
            logging("No VALID lastCheckin event available! This should be resolved by itself within 1 or 2 hours and is perfectly NORMAL as long as the same device don't get this multiple times per day...", 100)
            r = r == null ? -1 : r
        } else {
            r = (now() - device.currentValue('lastCheckinEpoch').toLong()) / 1000
        }
    }
    return r
}

boolean checkinIsRecent(Integer maximumMinutesBetweenEvents=90, boolean displayWarnings=true) {
    Long secondsSinceLastCheckin = secondsSinceCheckin()
    if(secondsSinceLastCheckin != null && secondsSinceLastCheckin > maximumMinutesBetweenEvents * 60) {
        if(displayWarnings == true && (presenceWarningEnable == null || presenceWarningEnable == true)) log.warn("One or several EXPECTED checkin events have been missed! Something MIGHT be wrong with the mesh for this device. Minutes since last checkin: ${Math.round(secondsSinceLastCheckin / 60)} (maximum expected $maximumMinutesBetweenEvents)")
        return false
    }
    return true
}

boolean checkPresence(boolean displayWarnings=true) {
    boolean isPresent = false
    Long lastCheckinTime = null
    String lastCheckinVal = device.currentValue('lastCheckin')
    if ((lastCheckinEnable == true || lastCheckinEnable == null) && isValidDate('yyyy-MM-dd HH:mm:ss', lastCheckinVal) == true) {
        lastCheckinTime = Date.parse('yyyy-MM-dd HH:mm:ss', lastCheckinVal).getTime()
    } else if (lastCheckinEpochEnable == true && device.currentValue('lastCheckinEpoch') != null) {
        lastCheckinTime = device.currentValue('lastCheckinEpoch').toLong()
    }
    if(lastCheckinTime != null && lastCheckinTime >= now() - (3 * 60 * 60 * 1000)) {
        markPresent()
        isPresent = true
    } else {
        sendEvent(name: "presence", value: "not present")
        if(displayWarnings == true) {
            Integer numNotPresent = device.currentValue('notPresentCounter')
            numNotPresent = numNotPresent == null ? 1 : numNotPresent + 1
            sendEvent(name: "notPresentCounter", value: numNotPresent)
            if(presenceWarningEnable == null || presenceWarningEnable == true) {
                log.warn("No event seen from the device for over 3 hours! Something is not right... (consecutive events: $numNotPresent)")
            }
        }
    }
    return isPresent
}

void markPresent() {
    if(device.currentValue('presence') == "not present") {
        Integer numRestored = device.currentValue('restoredCounter')
        numRestored = numRestored == null ? 1 : numRestored + 1
        sendEvent(name: "restoredCounter", value: numRestored)
        sendEvent(name: "notPresentCounter", value: 0)
    }
    sendEvent(name: "presence", value: "present")
}

void resetRestoredCounter() {
    logging("resetRestoredCounter()", 100)
    sendEvent(name: "restoredCounter", value: 0, descriptionText: "Reset restoredCounter to 0")
}

void initCounters() {
    if(device.currentValue('restoredCounter') == null) sendEvent(name: "restoredCounter", value: 0, descriptionText: "Initialized to 0")
    if(device.currentValue('notPresentCounter') == null) sendEvent(name: "notPresentCounter", value: 0, descriptionText: "Initialized to 0")
    if(device.currentValue('presence') == null) sendEvent(name: "presence", value: "unknown", descriptionText: "Initialized as Unknown")
}

void configurePresence() {
    initCounters()
    if(presenceEnable == null || presenceEnable == true) {
        Random rnd = new Random()
        schedule("${rnd.nextInt(59)} ${rnd.nextInt(59)} 1/3 * * ? *", 'checkPresence')
        checkPresence(false)
    } else {
        sendEvent(name: "presence", value: "not present", descriptionText: "Presence Checking Disabled")
        unschedule('checkPresence')
    }
}

/* ===== SENSOR EVENTS ===== */

void sendTemperatureEvent(Integer rawValue, BigDecimal variance = null, Integer minAllowed=-50, Integer maxAllowed=100) {
    if(variance == null) variance = tempVariance != null ? tempVariance.toBigDecimal() : 0.2

    List adjustedTemp = getAdjustedTemp(rawValue / 100.0)
    String tempUnit = adjustedTemp[0]
    BigDecimal t = adjustedTemp[1]
    BigDecimal tRaw = adjustedTemp[2]

    if(tRaw >= minAllowed && tRaw < maxAllowed) {
        BigDecimal oldT = device.currentValue('temperature') == null ? null : device.currentValue('temperature')
        if(oldT != null) oldT = oldT.setScale(1, BigDecimal.ROUND_HALF_UP)
        BigDecimal tChange = null
        if(oldT == null) {
            logging("Temperature: $t $tempUnit", 1)
        } else {
            tChange = Math.abs(t - oldT)
            tChange = tChange.setScale(1, BigDecimal.ROUND_HALF_UP)
            logging("Temperature: $t $tempUnit (old temp: $oldT, change: $tChange)", 1)
        }
        if(oldT == null || tChange > variance) {
            logging("Sending temperature event (Temperature: $t $tempUnit, old temp: $oldT, change: $tChange)", 100)
            sendEvent(name:"temperature", value: t, unit: "$tempUnit", isStateChange: true)
            if(reportAbsoluteHumidity == true) {
                sendAbsoluteHumidityEvent(tempInCelsius(t), device.currentValue('humidity'))
            }
        } else {
            logging("SKIPPING temperature event since the change wasn't large enough (Temperature: $t $tempUnit, old temp: $oldT, change: $tChange)", 1)
        }
    } else {
        log.warn "Incorrect temperature received from the sensor ($tRaw), it is probably time to change batteries!"
    }
}

void sendPressureEvent(Map msgMap) {
    def rawValue = msgMap['valueParsed']
    BigDecimal variance = 0.1
    if(msgMap["attrId"] == "0020") {
        rawValue = rawValue / 1000.0
    }
    // Sanity check: reject readings outside a physically plausible atmospheric range (hPa)
    if(rawValue < 500 || rawValue > 1100) {
        log.warn "Pressure reading out of range (${rawValue} hPa) — skipping. Check battery level."
        return
    }
    BigDecimal p = convertPressure(rawValue as BigDecimal)
    BigDecimal oldP = device.currentValue('pressure') == null ? null : device.currentValue('pressure')
    if(oldP != null) oldP = oldP.setScale(2, BigDecimal.ROUND_HALF_UP)
    BigDecimal pChange = null
    if(oldP == null) {
        logging("Pressure: $p", 1)
    } else {
        pChange = Math.abs(p - oldP)
        pChange = pChange.setScale(2, BigDecimal.ROUND_HALF_UP)
        logging("Pressure: $p (old pressure: $oldP, change: $pChange)", 1)
    }
    String pUnit = pressureUnitConversion == null ? "kPa" : pressureUnitConversion
    if(oldP == null || pChange > variance) {
        logging("Sending pressure event (Pressure: $p, old pressure: $oldP, change: $pChange)", 100)
        sendEvent(name:"pressure", value: p, unit: "$pUnit", isStateChange: true)
    } else {
        logging("SKIPPING pressure event since the change wasn't large enough (Pressure: $p, old pressure: $oldP, change: $pChange)", 1)
    }
}

void sendHumidityEvent(Integer rawValue, BigDecimal variance = null) {
    if(variance == null) variance = humidityVariance != null ? humidityVariance.toBigDecimal() : 0.5

    BigDecimal h = getAdjustedHumidity(rawValue / 100.0)
    BigDecimal oldH = device.currentValue('humidity')
    if(oldH != null) oldH = oldH.setScale(2, BigDecimal.ROUND_HALF_UP)
    BigDecimal hChange = null
    if(h <= 100) {
        if(oldH == null) {
            logging("Humidity: $h %", 1)
        } else {
            hChange = Math.abs(h - oldH)
            hChange = hChange.setScale(2, BigDecimal.ROUND_HALF_UP)
            logging("Humidity: $h% (old humidity: $oldH%, change: $hChange%)", 1)
        }
        if(oldH == null || hChange > variance) {
            logging("Sending humidity event (Humidity: $h%, old humidity: $oldH%, change: $hChange%)", 100)
            sendEvent(name:"humidity", value: h, unit: "%", isStateChange: true)
            if(reportAbsoluteHumidity == true) {
                sendAbsoluteHumidityEvent(tempInCelsius(), h)
            }
        } else {
            logging("SKIPPING humidity event since the change wasn't large enough (Humidity: $h%, old humidity: $oldH%, change: $hChange%)", 1)
        }
    }
}

/* ===== STYLING ===== */

String titleDiv(title) {
    return '<div class="preference-title">' + title + '</div>'
}

String descDiv(description) {
    return '<div class="preference-description">' + description + '</div>'
}

String defaultCSS(boolean includeTags=true) {
    String defaultCSS = '''
    div.mdl-card__title div.mdl-grid div.mdl-grid .mdl-cell p::after {
        visibility: visible;
        position: absolute;
        left: 50%;
        transform: translate(-50%, 0%);
        width: calc(100% - 20px);
        padding-left: 5px;
        padding-right: 5px;
        margin-top: 0px;
    }
    h3, h4, .property-label {
        font-weight: bold;
    }
    .preference-title {
        font-weight: bold;
    }
    .preference-description {
        font-style: italic;
    }
    '''
    if(includeTags == true) {
        return "<style>$defaultCSS </style>"
    } else {
        return defaultCSS
    }
}

/* ===== SENSOR DATA ===== */

private List getAdjustedTemp(BigDecimal value) {
    Integer res = 1
    BigDecimal rawValue = value
    if(tempRes != null && tempRes != '') {
        res = Integer.parseInt(tempRes)
    }
    String degree = String.valueOf((char)(176))
    String tempUnit = "${degree}C"
    String currentTempUnitDisplayed = tempUnitDisplayed
    if(currentTempUnitDisplayed == null || currentTempUnitDisplayed == "0") {
        if(location.temperatureScale == "C") {
            currentTempUnitDisplayed = "1"
        } else {
            currentTempUnitDisplayed = "2"
        }
    }
    if (currentTempUnitDisplayed == "2") {
        value = celsiusToFahrenheit(value)
        tempUnit = "${degree}F"
    } else if (currentTempUnitDisplayed == "3") {
        value = value + 273.15
        tempUnit = "${degree}K"
    }
    if (tempOffset != null) {
        return [tempUnit, (value + new BigDecimal(tempOffset)).setScale(res, BigDecimal.ROUND_HALF_UP), rawValue]
    } else {
        return [tempUnit, value.setScale(res, BigDecimal.ROUND_HALF_UP), rawValue]
    }
}

private BigDecimal tempInCelsius(BigDecimal providedCurrentTemp = null) {
    String currentTempUnitDisplayed = tempUnitDisplayed
    BigDecimal currentTemp = providedCurrentTemp != null ? providedCurrentTemp : device.currentValue('temperature')
    if(currentTempUnitDisplayed == null || currentTempUnitDisplayed == "0") {
        if(location.temperatureScale == "C") {
            currentTempUnitDisplayed = "1"
        } else {
            currentTempUnitDisplayed = "2"
        }
    }
    if (currentTempUnitDisplayed == "2") {
        currentTemp = fahrenheitToCelsius(currentTemp)
    } else if (currentTempUnitDisplayed == "3") {
        currentTemp = currentTemp - 273.15
    }
    return currentTemp
}

void sendAbsoluteHumidityEvent(BigDecimal deviceTempInCelsius, BigDecimal relativeHumidity) {
    if(relativeHumidity != null && deviceTempInCelsius != null) {
        BigDecimal numerator = (6.112 * Math.exp((17.67 * deviceTempInCelsius) / (deviceTempInCelsius + 243.5)) * relativeHumidity * 2.1674)
        BigDecimal denominator = deviceTempInCelsius + 273.15
        BigDecimal absHumidity = numerator / denominator
        String cubeChar = String.valueOf((char)(179))
        absHumidity = absHumidity.setScale(1, BigDecimal.ROUND_HALF_UP)
        logging("Sending Absolute Humidity event (Absolute Humidity: ${absHumidity}g/m${cubeChar})", 100)
        sendEvent(name: "absoluteHumidity", value: absHumidity, unit: "g/m${cubeChar}", descriptionText: "Absolute Humidity Is ${absHumidity} g/m${cubeChar}")
    }
}

private BigDecimal getAdjustedHumidity(BigDecimal value) {
    Integer res = 1
    if(humidityRes != null && humidityRes != '') {
        res = Integer.parseInt(humidityRes)
    }
    if (humidityOffset) {
        return (value + new BigDecimal(humidityOffset)).setScale(res, BigDecimal.ROUND_HALF_UP)
    } else {
        return value.setScale(res, BigDecimal.ROUND_HALF_UP)
    }
}

private BigDecimal getAdjustedPressure(BigDecimal value, Integer decimals=2) {
    Integer res = decimals
    if(pressureRes != null && pressureRes != '' && pressureRes != 'default') {
        res = Integer.parseInt(pressureRes)
    }
    if (pressureOffset) {
        return (value + new BigDecimal(pressureOffset)).setScale(res, BigDecimal.ROUND_HALF_UP)
    } else {
        return value.setScale(res, BigDecimal.ROUND_HALF_UP)
    }
}

private BigDecimal convertPressure(BigDecimal pressureInHPa) {
    BigDecimal pressure = pressureInHPa
    switch(pressureUnitConversion) {
        case null:
        case "kPa":
            pressure = getAdjustedPressure(pressure / 10)
            break
        case "inHg":
            pressure = getAdjustedPressure(pressure * 0.0295299)
            break
        case "mmHg":
            pressure = getAdjustedPressure(pressure * 0.75006157)
            break
        case "atm":
            pressure = getAdjustedPressure(pressure / 1013.25, 5)
            break
        default:
            // mbar = hPa, return as-is
            pressure = getAdjustedPressure(pressure, 1)
            break
    }
    return pressure
}
